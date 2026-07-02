# Serviço Distribuído de Lock com Tolerância a Falhas

Este repositório contém a implementação de um serviço distribuído de controle de concorrência (Lock) tolerante a falhas por travamento (CFT - *Crash Fault Tolerance*). O projeto foi desenvolvido como trabalho final da disciplina de Computação Distribuída, visando garantir a exclusão mútua no acesso a recursos compartilhados por múltiplos processos clientes em um ambiente de rede assíncrono.

## 1. Arquitetura Adotada
O sistema adota uma arquitetura descentralizada baseada no modelo **Cliente-Servidor** com instâncias conteinerizadas.
A topologia padrão de demonstração é composta por 6 nós independentes:
* **3 Réplicas de Servidor (`rep-server`):** Mantêm o estado dos locks em memória.
* **3 Instâncias de Cliente (`rep-client`):** Atuam como geradores de carga de trabalho independentes, solicitando recursos concorrentemente para demonstrar a eficácia do algoritmo.

A orquestração é feita via motor de contêineres (Docker/Podman), e a observabilidade do cluster é exibida em tempo real utilizando um terminal multiplexado (`tmux`).

## 2. Tecnologia de Comunicação
A interação entre os processos distribuídos (Cliente ↔ Servidor e Servidor ↔ Servidor) é realizada através de **Invocação Remota baseada em Web Services RESTful sobre protocolo HTTP**.
* As operações de concessão, renovação e liberação de locks são mapeadas para os verbos HTTP correspondentes (`POST`, `PUT`, `GET`, `DELETE`).
* Foi utilizado o `RestClient` nativo do framework **Spring Boot (Java 21)** para prover chamadas síncronas com tratamento explícito de *timeouts* e exceções de rede.

## 3. Estratégia de Replicação e Consenso
Para atender ao requisito de alta disponibilidade e consistência, o projeto implementa o modelo de **Replicação Passiva (Primary-Backup)** acoplado a um mecanismo de **Versionamento Lógico**, com failover dinâmico gerido pelos clientes.

* **Lista Estática:** Todos os clientes conhecem a topologia e a lista completa de servidores disponíveis.
* **Sincronização:** Toda requisição de mutação de estado (Lock/Renew/Unlock) é direcionada ao líder em exercício (Primary). O líder processa a regra de negócio e realiza chamadas HTTP síncronas (`/sync`) para replicar a alteração de estado nas réplicas (Backups) antes de confirmar o sucesso ao cliente.
* **Failover Ativo:** Não há eleição complexa de líder (como Raft ou Paxos) entre os servidores. A transição de liderança ocorre de forma oportunista pelo cliente: se o cliente detecta uma falha de conexão (timeout) com o Primary atual, ele rotaciona seu índice interno e passa a enviar requisições para o próximo nó da lista.
* **Failback e Prevenção de Split-Brain:** A cada nova transação de lock, o cliente reinicia seu índice de busca apontando obrigatoriamente para o `server-1`. Isso garante que, quando o servidor principal retornar de uma falha, ele reassuma imediatamente o protagonismo, forçando a convergência da rede e evitando a divisão do cluster com múltiplos líderes atendendo clientes simultaneamente.
* **Consenso por Versionamento Lógico (Epochs):** Para garantir a ordenação total dos eventos e prevenir a corrupção do estado durante a recuperação de partições de rede, o sistema adota Relógios Lógicos. Toda mutação bem-sucedida no Líder incrementa monotonicamente um contador global (*State Version*). Este número é replicado compulsoriamente para os *Backups*, servindo como assinatura de integridade para a resolução de conflitos.

## 4. Política de Concessão de Locks e Concorrência
A coordenação de recursos segue propriedades rigorosas de segurança (*safety*) e vivacidade (*liveness*), separando as garantias distribuídas das garantias locais de execução:

* **Exclusão Mútua Distribuída (Coordenador Centralizado):** A garantia de que dois processos na rede não acessem o recurso simultaneamente é assegurada pelo design da arquitetura: todas as decisões de escrita são afuniladas no Líder (*Primary*), que atua como a única fonte da verdade para a concessão de acessos.
* **Exclusão Mútua Local (Thread-Safety):** Dentro do processo do servidor Primário, a concorrência física entre requisições HTTP simultâneas é resolvida localmente na memória da JVM. Utiliza-se a estrutura `ConcurrentHashMap` para garantir operações atômicas baseadas em *Compare-And-Swap* (CAS) a nível de processador, impedindo Condições de Corrida (*Race Conditions*) internamente.
* **Fila Estrita (FIFO):** Requisições concorrentes a um recurso já bloqueado são enfileiradas de forma atômica. O servidor garante a justiça (*fairness*): mesmo que o lock físico seja liberado, o servidor negará o acesso a novos solicitantes caso eles não sejam estritamente os primeiros da fila (`queue.peek()`).
* **Active Waiting (Polling) vs Long Polling:** Quando um cliente entra na fila, ele não mantém a conexão HTTP aberta aguardando a liberação (abordagem de *Long Polling*). Para evitar a exaustão do *pool* de conexões do servidor sob alta carga, o sistema adota **Espera Ativa (Polling)**: o cliente recebe uma resposta imediata de "negado", sofre um bloqueio local (*sleep*) de 3 segundos, e realiza uma nova tentativa na API.
* **Leases (Tempo de Vida da Seção Crítica):** Todo lock é concedido com um Lease (prazo de validade) restrito a 10 segundos. Um processo assíncrono de *Eviction* no servidor varre a memória periodicamente e revoga permissões expiradas. O cliente detentor pode estender este tempo enviando um *Heartbeat* explícito através da operação `Renew`.
* **Segurança Operacional:** O servidor atua como mediador seguro, validando a identidade (`clientId`) no momento do *Unlock*, rejeitando requisições maliciosas onde um processo tenta liberar um lock de terceiros.

## 5. Tratamento de Falhas Sistêmicas
O sistema assume que falhas parciais são o comportamento padrão em sistemas distribuídos. Cenários tratados e demonstrados no ambiente de testes (`Modo Caos`):

1. **Crash do Cliente (Deadlock Tradicional):** Um cliente adquire um lock e sofre uma pane fatal. O sistema detecta a ausência de renovação e recupera o recurso passados 10 segundos via expiração de Lease.
2. **Prevenção de Deadlock Fantasma (Ghost Queue Deadlock):** Se o cliente que ocupa a *primeira posição na fila de espera* falhar silenciosamente, a fila travaria de forma permanente. Para evitar esse anômalo de concorrência, o servidor monitora *Heartbeats* passivos. Se um cliente na fila ficar mais de 12 segundos sem realizar *Polling*, o servidor o considera inoperante e o expulsa, permitindo o avanço orgânico da aplicação.
3. **Ataque / Liberação Indevida:** Um processo tenta executar um *Unlock* no recurso alheio. O servidor valida a propriedade e bloqueia a operação, preservando a integridade.
4. **Crash do Servidor Primary:** O primário sofre uma partição de rede temporária. Os clientes detectam o *Timeout* na camada HTTP e migram a carga dinamicamente para o nó backup.
5. **Fase Estrita de Recuperação de Nó:** Quando o servidor primário se recupera do *crash*, ele não atende clientes de imediato (o que geraria *stale reads* e descompasso de filas). Ele entra em uma fase de bloqueio transitório, varre os nós sobreviventes da rede, e realiza o pareamento por consenso: faz o *download* completo da árvore de locks apenas do nó que apresentar a **maior versão numérica de estado (*Epoch*)**. Após absorver a versão autoritativa vencedora, ele reabre as portas para o tráfego de rede.

## 6. Limitações Conhecidas
Como se trata de uma implementação acadêmica focada em demonstrar conceitos vitais de concorrência distribuída sem a complexidade excessiva de bibliotecas *production-ready*, algumas concessões arquiteturais foram feitas:

* **Sincronicidade Estrita:** A replicação para os backups utiliza semântica bloqueante. Uma eventual degradação de performance em um nó backup impactará diretamente a latência final percebida pelo cliente durante a aquisição do lock.
* **Clock Drift:** A expiração de *Leases* depende estritamente do relógio de parede (*wall-clock time*) do servidor primário. Variações severas de sincronização de hardware (*clock drift*) entre as instâncias em um ambiente real de nuvem poderiam introduzir *jitters* temporais nas revogações.

## 7. Como Executar o Projeto
O sistema é acompanhado de um script Bash completo que abstrai a construção (*multi-stage builds*) e o roteamento da rede dos contêineres.

**Pré-requisitos:** Docker (ou Podman) e o multiplexador de terminal `tmux` instalados nativamente no Linux.

1. Conceda permissão de execução ao script:
```bash
   chmod +x apresentacao.sh
```
   
2. O script dividirá o terminal de forma simétrica em 6 painéis. O próprio sistema gerará uma carga contínua (narrada no log com o prefixo 🎬 ), disparando cenários de concorrência e simulaçao de falhas para facilitar a avaliação visual dos requisitos.