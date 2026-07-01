package com.dutra.rep_client.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.util.Arrays;
import java.util.List;

@Service
public class ClientLockService {

    private final String clientId = System.getenv("CLIENT_ID");
    private final List<String> servers = Arrays.asList(System.getenv("SERVER_LIST").split(","));
    private int currentLeaderIndex = 0;
    private final RestClient restClient = RestClient.create();

    public void solicitarLock(String resourceId) {
        System.out.printf("🟢 [%s] Solicitou LOCK para '%s'%n", clientId, resourceId);

        boolean success = false;
        int attempts = 0;

        while (!success && attempts < servers.size()) {
            String leaderUrl = servers.get(currentLeaderIndex);
            try {
                Boolean granted = restClient.post()
                        .uri(leaderUrl + "/api/lock/" + resourceId + "?clientId=" + clientId)
                        .retrieve()
                        .body(Boolean.class);

                success = true; // Comunicação bem sucedida com o Primary

                if (Boolean.TRUE.equals(granted)) {
                    System.out.printf("🔐 [%s] Operando no recurso '%s' com sucesso!%n", clientId, resourceId);

                    // 1. Simula a retenção do lock por um tempo
                    simularProcessamento();

                    // 2. Libera o lock após o uso
                    liberarLock(resourceId, leaderUrl);
                } else {
                    System.out.printf("⏳ [%s] Recurso ocupado. Aguardando na fila...%n", clientId);
                }

            } catch (RestClientException e) {
                System.out.printf("💥 [%s] Falha ao contatar Primary (%s). Timeout/Erro de Conexão!%n", clientId, leaderUrl);

                // Substituição de líder em caso de falha de comunicação
                currentLeaderIndex = (currentLeaderIndex + 1) % servers.size();
                System.out.printf("🔍 [%s] Substituição de líder. Tentando próximo servidor: %s...%n", clientId, servers.get(currentLeaderIndex));
                attempts++;
            }
        }

        if (!success) {
            System.out.printf("💀 [%s] SISTEMA INDISPONÍVEL. Nenhum servidor respondeu.%n", clientId);
        }
    }

    private void simularProcessamento() {
        try {
            // Segura o lock por 5 segundos para evidenciar a concorrência no terminal
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.printf("⚠️ [%s] Operação simulada foi interrompida.%n", clientId);
        }
    }

    private void liberarLock(String resourceId, String leaderUrl) {
        try {
            // Chama o endpoint HTTP DELETE no servidor para liberar o recurso
            restClient.delete()
                    .uri(leaderUrl + "/api/lock/" + resourceId + "?clientId=" + clientId)
                    .retrieve()
                    .toBodilessEntity();

            System.out.printf("🔓 [%s] LOCK liberado com sucesso em '%s'.%n", clientId, resourceId);
        } catch (RestClientException e) {
            System.out.printf("❌ [%s] Erro ao tentar liberar o LOCK no servidor %s.%n", clientId, leaderUrl);
        }
    }
}