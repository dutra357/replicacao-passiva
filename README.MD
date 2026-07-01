# Serviço Distribuído de Lock com Tolerância a Falhas

Este repositório contém a implementação de um serviço distribuído de controle de concorrência (Lock) tolerante a falhas. O projeto foi desenvolvido como trabalho final da disciplina de Computação Distribuída, visando garantir a exclusão mútua no acesso a recursos compartilhados por múltiplos clientes.

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
Para atender ao requisito de alta disponibilidade, o projeto implementa o modelo de **Replicação Passiva (Primary-Backup)** com failover dinâmico gerido pelo cliente.

* **Lista Estática:** Todos os clientes conhecem a lista completa de servidores disponíveis.
* **Sincronização:** Toda requisição de escrita (Lock/Renew/Unlock) é direcionada ao líder em exercício (Primary). O líder processa a regra de negócio e, antes de confirmar o sucesso ao cliente, realiza chamadas HTTP síncronas (`/sync`) para replicar a alteração de estado nas réplicas (Backups).
* **Failover Ativo:** Não há eleição complexa de líder (ex: Raft) entre os servidores. A transição de liderança ocorre ativamente pelo cliente: se o cliente detecta uma falha de conexão (timeout) com o Primary atual, ele rotaciona seu índice interno e passa a enviar requisições para o próximo nó da lista.
* **Failback e Prevenção de Split-Brain:** A cada nova transação (solicitação de um novo lock), o cliente reinicia seu índice de busca apontando obrigatoriamente para o `server-1`. Isso garante que, quando o servidor principal se recuperar de uma falha e restaurar sua memória, ele reassuma imediatamente o protagonismo, evitando que a rede fique dividida com dois líderes atendendo clientes diferentes de forma simultânea.

## 4. Política de Concessão de Locks e Concorrência
A coordenação de recursos segue propriedades rigorosas de segurança e vivacidade:

* **Exclusão Mútua:** Implementada através de estruturas *Thread-Safe* (`ConcurrentHashMap`), garantindo que apenas um cliente detenha o lock válido de um recurso por vez.
* **Fila Estrita (FIFO):** Requisições concorrentes a um recurso bloqueado são enfileiradas. O servidor garante a justiça (*fairness*): mesmo que o lock físico seja liberado, o servidor negará o acesso a novos solicitantes caso eles não sejam estritamente os primeiros da fila (`queue.peek()`).
* **Active Waiting (Polling) vs Long Polling:** Quando um cliente entra na fila, ele não mantém a conexão HTTP aberta aguardando a liberação (abordagem de *Long Polling*). O *Long Polling* esgotaria rapidamente o pool de *threads* do servidor em cenários de alta concorrência. Em vez disso, o sistema utiliza **Espera Ativa (Polling)**: o cliente recebe uma resposta imediata de "negado", dorme localmente por 3 segundos, e realiza uma nova requisição independente.
* **Leases (Tempo de Vida da Seção Crítica):** Todo lock é concedido com um Lease (prazo de validade) de 10 segundos. Um processo de *Eviction* no servidor varre a memória periodicamente e revoga permissões expiradas. O cliente pode estender esse tempo usando a operação `Renew`.
* **Segurança na Liberação:** O servidor valida a identidade (`clientId`) no momento do *Unlock*, rejeitando ataques onde um processo tenta liberar um lock que pertence a terceiros.

## 5. Tratamento de Falhas Sistêmicas
O sistema foi projetado sob a premissa de que falhas de rede e processos são a regra, não a exceção. Cenários tratados e demonstrados via script de automação (`Modo Caos`):

1. **Crash do Cliente (Deadlock Tradicional):** Um cliente adquire um lock e simula uma pane fatal. O sistema se recupera passados 10 segundos via expiração de Lease.
2. **Prevenção de Deadlock Fantasma (Ghost Queue Deadlock):** E se o cliente que estiver em *primeiro na fila de espera* falhar antes de pedir o lock de novo? A fila travaria para sempre. Para evitar isso, o servidor monitora *Heartbeats* passivos. Se um cliente na fila ficar mais de 12 segundos sem fazer *Polling*, o servidor o considera inoperante e o expulsa da fila, permitindo que a aplicação flua.
3. **Ataque Malicioso:** Um cliente tenta executar um *Unlock* indevido no recurso de outro. O servidor detecta a anomalia e bloqueia a operação.
4. **Crash do Servidor Primary:** O primário isola-se da rede momentaneamente. Os clientes detectam o *Timeout* e migram silenciosamente para o backup.
5. **Recuperação de Nó (CFT - Crash Fault Tolerance):** Quando o servidor primário retorna da pane, ele não atende clientes de imediato. Ele entra em "Modo de Recuperação", faz o download da árvore de locks e filas atualizadas do backup que estava atuando como líder, e só então volta a aceitar requisições.

## 6. Limitações Conhecidas
Como se trata de uma implementação acadêmica focada em simplicidade arquitetural, algumas concessões foram feitas:

* **Sincronicidade Estrita:** A replicação para os backups é bloqueante. Se um backup estiver operando de forma lenta, a latência percebida pelo cliente ao requisitar um lock aumentará proporcionalmente.
* **Clock Drift:** A expiração de *Leases* depende do relógio físico do servidor primário. Diferenças severas de sincronização de hardware (*clock drift*) entre as máquinas do cluster poderiam afetar marginalmente a precisão temporal dos bloqueios.

## 7. Como Executar o Projeto
O sistema é acompanhado de um script Bash completo que abstrai a construção (*multi-stage builds*) e o roteamento de rede.

**Pré-requisitos:** Docker (ou Podman) e o multiplexador de terminal `tmux` instalados nativamente no Linux.

1. Conceda permissão de execução ao script:
   ```bash
   chmod +x apresentacao.sh