package com.dutra.rep_client;

import com.dutra.rep_client.service.ClientLockService;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Random;

@Component
@EnableScheduling
public class GeradorDeCarga {

    private final ClientLockService lockService;
    private final String clientId = System.getenv("CLIENT_ID");
    private final Random random = new Random();

    private final String[] recursos = {"arquivo-1", "registro-42", "impressora-A", "dataset-X"};

    public GeradorDeCarga(ClientLockService lockService) {
        this.lockService = lockService;
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 10000)
    public void executarCarga() {

        String resource = recursos[random.nextInt(recursos.length)];

        if (clientId.equals("client-1")) {
            if (random.nextBoolean()) {
                // 50% de chance para rodar a operação longa que exige renovação
                System.out.printf("🎬 [%s] DEMONSTRAÇÃO: Iniciando operação longa em '%s' (Testando RENEW)%n", clientId, resource);
                lockService.solicitarLockComOperacaoLonga(resource);

            } else {
                // 50% de chance para lock normal
                System.out.printf("🎬 [%s] DEMONSTRAÇÃO: Requisição normal de Lock em '%s'%n", clientId, resource);
                lockService.solicitarLock(resource);
            }

        } else if (clientId.equals("client-2")) {

            double chance = random.nextDouble();

            if (chance < 0.6) {
                // 60% para concorrencia de outro lock
                String alvoId = random.nextBoolean() ? "client-1" : "client-3";
                System.out.printf("🎬 [%s] FALHA - Tentativa de liberar '%s' (LOCK para %s)%n", clientId, resource, alvoId);
                lockService.tentarLiberarIndevidamente(resource, alvoId);

            } else if (chance < 0.8) {
                // 20% para crash
                System.out.printf("🎬 [%s] FALHA - Crash stop após reter '%s' (Testando LEASE)%n", clientId, resource);
                lockService.solicitarLockComFalhaSimulada(resource);

            } else {
                // 20% para operação normal
                System.out.printf("🎬 [%s] Requisição normal de Lock em '%s'%n", clientId, resource);
                lockService.solicitarLock(resource);
            }

        } else if (clientId.equals("client-3")) {

            System.out.printf("🎬 [%s] Consultando Status de '%s'%n", clientId, resource);
            lockService.consultarStatus(resource);

            if (random.nextDouble() < 0.3) {
                System.out.printf("🎬 [%s] FALHA - Tentativa de liberar '%s' (LOCK para o client-2)%n", clientId, resource);
                lockService.tentarLiberarIndevidamente(resource, "client-2");
            }

            System.out.printf("🎬 [%s] Requisição de Lock em '%s' após análise de status de LOCK.%n", clientId, resource);
            lockService.solicitarLock(resource);
        }
    }
}