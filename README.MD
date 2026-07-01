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
* As operações de concessão, renovação e liberação de locks são mapeadas para os verbos HTTP correspondentes (`POST`, `GET`, `DELETE`).
* Foi utilizado o `RestClient` nativo do framework **Spring Boot (Java 21)** para prover chamadas síncronas com tratamento explícito de *timeouts* e exceções de rede.

## 3. Estratégia de Replicação
Para atender ao requisito de alta disponibilidade, o projeto implementa o modelo de **Replicação Passiva (Primary-Backup)** com failover dinâmico gerido pelo cliente.

* **Lista Estática:** Todos os clientes conhecem a lista completa de servidores disponíveis.
* **Sincronização:** Toda requisição de escrita (Lock/Unlock) é direcionada ao líder em exercício (Primary). O líder processa a regra de negócio e, antes de confirmar o sucesso ao cliente, realiza chamadas HTTP síncronas (`/sync`) para replicar a alteração de estado nas réplicas (Backups).
* **Failover Ativo:** Não há eleição complexa de líder (ex: Raft) entre os servidores. A transição de liderança ocorre ativamente pelo cliente: se o cliente detecta uma falha de conexão (timeout) com o Primary atual, ele rotaciona seu índice interno e passa a enviar requisições para o próximo nó da lista.

## 4. Política de Concessão de Locks e Leases
A coordenação de recursos segue as seguintes propriedades de segurança e vivacidade:

* **Exclusão Mútua:** Implementada através de estruturas *Thread-Safe* (`ConcurrentHashMap`), garantindo que apenas um cliente detenha o lock válido de um recurso por vez.
* **Fila de Espera:** Requisições concorrentes a um recurso já bloqueado são negadas momentaneamente e o ID do solicitante é inserido em uma fila de espera (`waitQueue`). O cliente realiza *polling* ativo (retentativas) até adquirir o recurso.
* **Leases (Tempo de Vida):** Para evitar *deadlocks* sistêmicos causados por clientes que falham antes de liberar o recurso (crash do processo solicitante), todo lock é concedido com um Lease de 10 segundos. Um processo em *background* varre os locks periodicamente e revoga permissões expiradas.
* **Segurança na Liberação:** O servidor valida a identidade (`clientId`) no momento do *Unlock*, rejeitando ataques onde um processo tenta liberar um lock que pertence a terceiros.

## 5. Tratamento de Falhas
O sistema foi projetado sob a premissa de que falhas de rede e processos são a regra, não a exceção. Cenários tratados e demonstrados via script de automação (`Modo Caos`):

1. **Crash do Cliente:** Simulado pelo `client-2`, que adquire um lock e simula uma pane fatal. O sistema se recupera passados 10 segundos via expiração de Lease.
2. **Ataque Malicioso:** Simulado pelo `client-3`, que tenta executar um *Unlock* no recurso pertencente ao `client-1`. O servidor bloqueia a operação, preservando a segurança.
3. **Crash do Servidor Primary:** O `server-1` isola-se da rede momentaneamente. Os clientes detectam o *Timeout* e migram silenciosamente para o `server-2`.
4. **Recuperação de Nó (CFT - Crash Fault Tolerance):** Quando um servidor primário retorna de uma falha, ele não assume requisições imediatamente (o que causaria estado inconsistente). Ele entra em modo "Recuperação", faz o download da árvore de locks do novo líder e reintegra-se ao cluster de forma passiva.

## 6. Limitações Conhecidas
Como se trata de uma implementação acadêmica focada em simplicidade arquitetural, algumas concessões foram feitas:

* **Sincronicidade Estrita:** A replicação para os backups é bloqueante. Se um backup estiver operando de forma lenta, a latência percebida pelo cliente ao requisitar um lock aumentará proporcionalmente.
* **Risco de Inconsistência Momentânea (Split-Brain):** Como o failover é guiado pelo cliente e não há um mecanismo de Quórum para confirmar a escrita, se dois clientes sofrerem partições de rede distintas (enxergando listas de servidores diferentes), eles poderiam teoricamente eleger líderes diferentes. Em ambientes de produção críticos, algoritmos de consenso como Raft ou ZooKeeper (ZAB) seriam mandatórios.
* **Clock Drift:** A expiração de *Leases* depende do relógio do servidor que cedeu o lock. Diferenças severas de tempo físico entre as máquinas hospedeiras poderiam afetar marginalmente a precisão temporal.

## 7. Como Executar o Projeto
O sistema é acompanhado de um script Bash completo que abstrai a construção (multi-stage Docker builds) e o roteamento de rede.

**Pré-requisitos:** Docker (ou Podman) e o multiplexador de terminal `tmux` instalados.

1. Conceda permissão de execução ao script:
   ```bash
   chmod +x apresentacao.sh