package com.dutra.ufsc.lock_client.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Random;

@Service
public class WorkloadGenerator {

    private final Logger log = LoggerFactory.getLogger(WorkloadGenerator.class);

    private final String clientId;
    private final RestClient restClient;
    private final Random random = new Random();

    // Uma lista fixa de recursos para gerar contenção (fila) no servidor
    private final String[] recursos = {"arquivo-1", "dataset-X", "impressora-A"};

    public WorkloadGenerator(
            @Value("${CLIENT_ID:Cliente-Local}") String clientId,
            @Value("${SERVER_URL:http://localhost:8080}") String serverUrl) {

        this.clientId = clientId;
        this.restClient = RestClient.builder().baseUrl(serverUrl).build();

        log.info("Iniciando gerador de carga para o cliente [{}] apontando para [{}]", clientId, serverUrl);
    }

    /**
     * FLUXO PRINCIPAL: Simula clientes solicitando recursos, usando-os e (talvez) falhando.
     * Roda sempre entre 8 a 15 segundos para dar tempo de observarmos os logs com calma.
     */
    @Scheduled(fixedDelayString = "#{new java.util.Random().nextInt(7000) + 8000}")
    public void generateWorkload() {
        String resourceId = recursos[random.nextInt(recursos.length)];

        log.info("➡️ [{}] Solicitando lock para o recurso '{}'...", clientId, resourceId);

        try {
            // 1. SOLICITA O LOCK (Fica bloqueado aqui se houver fila, pois o servidor usa Long Polling com CompletableFuture)
            ResponseEntity<String> lockResponse = restClient.post()
                    .uri("/api/locks/{resourceId}/lock?clientId={clientId}", resourceId, clientId)
                    .retrieve()
                    .toEntity(String.class);

            if (lockResponse.getStatusCode().is2xxSuccessful()) {
                log.info("✅ [{}] Lock ADQUIRIDO no recurso '{}'!", clientId, resourceId);

                // 2. MODO CAOS: Simulação de Falha (20% de chance)
                if (random.nextDouble() < 0.2) {
                    log.error("💀 MODO CAOS: [{}] sofreu uma 'falha fatal' logo após pegar o lock '{}'! (Simulando sleep de 15s para forçar expiração de lease...)", clientId, resourceId);

                    // Dorme por 15s. Como o lease no servidor é de 10s, o servidor vai expirar o lock sozinho!
                    Thread.sleep(15000);

                    log.warn("🧟 [{}] 'Ressuscitou' após a falha, mas seu lock já deve ter expirado no servidor.", clientId);
                    return; // Retorna sem chamar o unlock, provando o funcionamento do Lease
                }

                // 3. FLUXO NORMAL: Trabalha e depois libera
                log.info("⚙️ [{}] Processando/utilizando o recurso '{}' por 3 segundos...", clientId, resourceId);
                Thread.sleep(3000);

                // 4. LIBERA O LOCK (Unlock)
                log.info("🔓 [{}] Liberando o recurso '{}'...", clientId, resourceId);
                restClient.post()
                        .uri("/api/locks/{resourceId}/unlock?clientId={clientId}", resourceId, clientId)
                        .retrieve()
                        .toBodilessEntity();

                log.info("🏁 [{}] Operação concluída com sucesso em '{}'.\n", clientId, resourceId);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("❌ [{}] Falha na comunicação com o servidor: {}", clientId, e.getMessage());
        }
    }

    /**
     * MODO CAOS (TENTATIVA INVÁLIDA): A cada 20 segundos, tenta dar "unlock" num recurso aleatório
     * passando um clientId falso, para testar o Requisito de Segurança (403 Forbidden).
     */
    @Scheduled(fixedRate = 20000)
    public void simulateMaliciousUnlock() {
        String resourceId = recursos[random.nextInt(recursos.length)];
        String fakeClientId = "Hacker-" + random.nextInt(999);

        log.warn("🕵️‍♂️ MODO CAOS: Cliente malicioso '{}' tentando dar unlock no recurso '{}' sem ser o dono...", fakeClientId, resourceId);

        try {
            restClient.post()
                    .uri("/api/locks/{resourceId}/unlock?clientId={clientId}", resourceId, fakeClientId)
                    .retrieve()
                    .toBodilessEntity();

            log.error("🚨 FALHA DE SEGURANÇA! O servidor permitiu que o hacker liberasse o lock!");

        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(403)) {
                log.info("🛡️ SEGURANÇA FUNCIONOU: Servidor bloqueou a ação maliciosa com HTTP 403 (Forbidden).\n");
            } else {
                log.warn("⚠️ Servidor retornou erro inesperado durante tentativa maliciosa: {}", e.getStatusCode());
            }
        } catch (Exception e) {
            log.warn("⚠️ Servidor indisponível durante teste de segurança: {}", e.getMessage());
        }
    }
}