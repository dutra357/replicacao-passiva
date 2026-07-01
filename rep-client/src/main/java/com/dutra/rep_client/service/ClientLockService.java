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

    // Fluxo normal com Fila de Espera (Polling)
    public void solicitarLock(String resourceId) {
        System.out.printf("🟢 [%s] Solicitando LOCK para '%s'...%n", clientId, resourceId);
        boolean granted = false;

        // REQUISITO 9: Aguarda ativamente na fila de espera até conseguir
        while (!granted) {
            granted = tentarAdquirir(resourceId);
            if (granted) {
                System.out.printf("🔐 [%s] Operando no recurso '%s'...%n", clientId, resourceId);
                simularProcessamento(4000);
                liberarLock(resourceId);
            } else {
                System.out.printf("⏳ [%s] Na fila de espera. Retentando em 3s...%n", clientId);
                simularProcessamento(3000); // Aguarda antes de tentar de novo
            }
        }
    }

    // REQUISITO 7: Simula falha do cliente (Pega o lock e "morre")
    public void solicitarLockComFalhaSimulada(String resourceId) {
        System.out.printf("😈 [%s] MODO CAOS: Tentando pegar o lock e sumir (Simular Crash)...%n", clientId);
        boolean granted = tentarAdquirir(resourceId);
        if (granted) {
            System.out.printf("💥 [%s] CRASH SIMULADO! Morri com o lock do '%s' na mão!%n", clientId, resourceId);
            // NÃO chama o liberarLock(), forçando o servidor a usar o Lease
        }
    }

    // REQUISITO 7: Simula ataque (Tenta liberar lock dos outros)
    public void tentarLiberarIndevidamente(String resourceId, String alvoId) {
        System.out.printf("🏴‍☠️ [%s] MODO CAOS: Tentando roubar/liberar lock do %s!%n", clientId, alvoId);
        String leaderUrl = servers.get(currentLeaderIndex);
        try {
            restClient.delete()
                    .uri(leaderUrl + "/api/lock/" + resourceId + "?clientId=" + clientId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            System.out.printf("⚠️ [%s] Falha de rede ao tentar ataque.%n", clientId);
        }
    }

    private boolean tentarAdquirir(String resourceId) {
        int attempts = 0;
        while (attempts < servers.size()) {
            String leaderUrl = servers.get(currentLeaderIndex);
            try {
                Boolean granted = restClient.post()
                        .uri(leaderUrl + "/api/lock/" + resourceId + "?clientId=" + clientId)
                        .retrieve()
                        .body(Boolean.class);
                return Boolean.TRUE.equals(granted);
            } catch (RestClientException e) {
                System.out.printf("💥 [%s] Primary (%s) caiu! Timeout.%n", clientId, leaderUrl);
                currentLeaderIndex = (currentLeaderIndex + 1) % servers.size();
                System.out.printf("🔍 [%s] Failover: Tentando %s...%n", clientId, servers.get(currentLeaderIndex));
                attempts++;
            }
        }
        return false;
    }

    private void simularProcessamento(int tempoMs) {
        try { Thread.sleep(tempoMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void liberarLock(String resourceId) {
        String leaderUrl = servers.get(currentLeaderIndex);
        try {
            restClient.delete()
                    .uri(leaderUrl + "/api/lock/" + resourceId + "?clientId=" + clientId)
                    .retrieve()
                    .toBodilessEntity();
            System.out.printf("🔓 [%s] Lock liberado honestamente.%n", clientId);
        } catch (Exception e) {
            System.out.printf("❌ [%s] Erro ao liberar o LOCK.%n", clientId);
        }
    }
}