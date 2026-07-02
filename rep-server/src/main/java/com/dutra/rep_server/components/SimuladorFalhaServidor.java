package com.dutra.rep_server.components;

import com.dutra.rep_server.service.LockService;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class SimuladorFalhaServidor {

    private final LockService lockService;
    private final List<String> servers;
    private final RestClient restClient = RestClient.create();
    private final String myServerId;

    public SimuladorFalhaServidor(LockService lockService) {
        this.lockService = lockService;
        this.servers = Arrays.asList(System.getenv("SERVER_LIST").split(","));
        this.myServerId = System.getenv("SERVER_ID");
    }

    @Scheduled(initialDelay = 30000, fixedDelay = 120000)
    public void simularQuedaERecuperacao() {
        if (myServerId == null || !myServerId.trim().equalsIgnoreCase("server-1")) {
            return;
        }

        System.out.printf("💥💥💥 [%s] MODO CAOS: Isolando servidor do cluster por 10s (Simulação de Pane)...%n", myServerId);
        lockService.setCrashed(true);

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.printf("🔌 [%s] Rede restaurada. Iniciando Varredura Consensual de Estado...%n", myServerId);

        Map<String, Object> vencedorState = null;
        long maiorVersaoEncontrada = -1;
        String noVencedor = "Nenhum";

        // Coleta e compara as versões de todos os nós sobreviventes do cluster
        for (String node : servers) {
            if (!node.contains(myServerId)) {
                try {
                    Map<String, Object> nodeState = restClient.get()
                            .uri(node + "/api/lock/state")
                            .retrieve()
                            .body(new ParameterizedTypeReference<Map<String, Object>>() {});

                    if (nodeState != null && nodeState.containsKey("version")) {
                        long noVersion = ((Number) nodeState.get("version")).longValue();
                        System.out.printf("🔍 [%s] Resposta recebida de %s na versão v%d.%n", myServerId, node, noVersion);

                        if (noVersion > maiorVersaoEncontrada) {
                            maiorVersaoEncontrada = noVersion;
                            vencedorState = nodeState;
                            noVencedor = node;
                        }
                    }
                } catch (Exception e) {
                    System.out.printf("⚠️ [%s] Nó %s inacessível ou offline durante recuperação.%n", myServerId, node);
                }
            }
        }

        // Importa estritamente a maior versão lógica para garantir a consistência de dados
        if (vencedorState != null) {
            lockService.importState(vencedorState);
            System.out.printf("✅ [%s] Consenso atingido. Estado importado de %s na versão v%d.%n", myServerId, noVencedor, maiorVersaoEncontrada);
        } else {
            System.out.printf("⚠️ [%s] Nenhum backup disponível respondeu. Mantendo memória limpa.%n", myServerId);
        }

        lockService.setCrashed(false);
        System.out.printf("🛡️ [%s] Servidor reincorporado e pronto para operação como BACKUP ativo.%n", myServerId);
    }
}