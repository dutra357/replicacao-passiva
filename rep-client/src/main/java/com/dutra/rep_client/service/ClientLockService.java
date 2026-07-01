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

        this.currentLeaderIndex = 0;
        System.out.printf("🟢 [%s] Solicitando LOCK para '%s'...%n", clientId, resourceId);

        boolean granted = false;

        while (!granted) {
            granted = tentarAdquirir(resourceId);
            if (granted) {
                System.out.printf("🔐 [%s] Trabalhando com lock concedido para '%s'...%n", clientId, resourceId);

                simularProcessamento(4000);

                liberarLock(resourceId);

            } else {
                System.out.printf("⏳ [%s] Na fila de espera. Retry em 3s...%n", clientId);

                simularProcessamento(3000);
            }
        }
    }

    // Falha de cliebnt
    public void solicitarLockComFalhaSimulada(String resourceId) {

        this.currentLeaderIndex = 0;
        boolean granted = tentarAdquirir(resourceId);

        if (granted) {
            System.out.printf("💥 [%s] FALHA SIMULADA: Processo cai sem liberar lock do '%s'. %n", clientId, resourceId);
        }
    }

    public void tentarLiberarIndevidamente(String resourceId, String alvoId) {

        this.currentLeaderIndex = 0;
        System.out.printf("🏴‍☠️ [%s] TESTE: Tentando liberar lock de outro cliente %s!%n", clientId, alvoId);

        String leaderUrl = servers.get(currentLeaderIndex);

        try {
            restClient.delete()
                    .uri(leaderUrl + "/api/lock/" + resourceId + "?clientId=" + clientId)
                    .retrieve()
                    .toBodilessEntity();

        } catch (Exception e) {
            System.out.printf("⚠️ [%s] Falha ao tentar lock ja concedido.%n", clientId);
        }
    }

    private boolean tentarAdquirir(String resourceId) {

        this.currentLeaderIndex = 0;
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
                System.out.printf("💥 [%s] PRIMARY (%s) caiu! Timeout.%n", clientId, leaderUrl);

                currentLeaderIndex = (currentLeaderIndex + 1) % servers.size();

                System.out.printf("🔍 [%s] Failover: Tentando %s...%n", clientId, servers.get(currentLeaderIndex));

                attempts++;
            }
        }
        return false;
    }

    private void liberarLock(String resourceId) {

        this.currentLeaderIndex = 0;
        String leaderUrl = servers.get(currentLeaderIndex);

        try {
            restClient.delete()
                    .uri(leaderUrl + "/api/lock/" + resourceId + "?clientId=" + clientId)
                    .retrieve()
                    .toBodilessEntity();

            System.out.printf("🔓 [%s] Solicitada liberacao / Lock liberado com sucesso.%n", clientId);

        } catch (Exception e) {
            System.out.printf("❌ [%s] Erro ao liberar o LOCK.%n", clientId);
        }
    }

    public void solicitarLockComOperacaoLonga(String resourceId) {

        this.currentLeaderIndex = 0;
        System.out.printf("🟢 [%s] Solicitando LOCK (Operação Longa) para '%s'...%n", clientId, resourceId);

        boolean granted = false;

        while (!granted) {
            granted = tentarAdquirir(resourceId);
            if (granted) {
                System.out.printf("🔐 [%s] Operação longa iniciada. Precisará de RENEW no meio do caminho...%n", clientId);

                simularProcessamento(6000);

                renovarLock(resourceId);

                simularProcessamento(6000);
                liberarLock(resourceId);

            } else {
                System.out.printf("⏳ [%s] Na fila de espera. Retentando em 3s...%n", clientId);
                simularProcessamento(3000);
            }
        }
    }

    public boolean renovarLock(String resourceId) {

        this.currentLeaderIndex = 0;
        System.out.printf("♻️ [%s] Solicitando RENOVAÇÃO (RENEW) para '%s'...%n", clientId, resourceId);

        int attempts = 0;

        while (attempts < servers.size()) {
            String leaderUrl = servers.get(currentLeaderIndex);

            try {
                Boolean granted = restClient.put()
                        .uri(leaderUrl + "/api/lock/" + resourceId + "/renew?clientId=" + clientId)
                        .retrieve()
                        .body(Boolean.class);

                if (Boolean.TRUE.equals(granted)) {
                    System.out.printf("✅ [%s] Lock renovado com sucesso! Mais tempo de processamento garantido.%n", clientId);
                    return true;

                } else {
                    System.out.printf("⚠️ [%s] Servidor recusou a renovação.%n", clientId);
                    return false;
                }

            } catch (RestClientException e) {
                System.out.printf("💥 [%s] Primary (%s) caiu ao tentar renovar! Timeout.%n", clientId, leaderUrl);
                currentLeaderIndex = (currentLeaderIndex + 1) % servers.size();
                attempts++;
            }
        }
        return false;
    }


    public void consultarStatus(String resourceId) {

        this.currentLeaderIndex = 0;
        String leaderUrl = servers.get(currentLeaderIndex);

        try {
            String status = restClient.get()
                    .uri(leaderUrl + "/api/lock/" + resourceId + "/status")
                    .retrieve()
                    .body(String.class);

            System.out.printf("📊 [%s] STATUS de '%s': %s%n", clientId, resourceId, status);

        } catch (RestClientException e) {
            System.out.printf("⚠️ [%s] Falha ao consultar status. Primary (%s) inoperante.%n", clientId, leaderUrl);

            currentLeaderIndex = (currentLeaderIndex + 1) % servers.size();
        }
    }

    private void simularProcessamento(int tempoMs) {
        try { Thread.sleep(tempoMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}