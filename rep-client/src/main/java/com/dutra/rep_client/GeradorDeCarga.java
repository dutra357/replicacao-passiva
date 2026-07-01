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

    @Scheduled(initialDelay = 5000, fixedDelay = 15000)
    public void executarCarga() {

        String resource = recursos[random.nextInt(recursos.length)];

        if (clientId.equals("client-1")) {
            if (random.nextBoolean()) {

                // 50% para rodar a operação longa que exige renovação (RENEW)
                lockService.solicitarLockComOperacaoLonga(resource);

            } else {
                // 50% de chance para lock normal
                lockService.solicitarLock(resource);
            }

        } else if (clientId.equals("client-2")) {

            double chance = random.nextDouble();

            if (chance < 0.6) {
                String alvoId = random.nextBoolean() ? "client-1" : "client-3";
                lockService.tentarLiberarIndevidamente(resource, alvoId);

            } else if (chance < 0.8) {
                lockService.solicitarLockComFalhaSimulada(resource);
            } else {
                lockService.solicitarLock(resource);
            }

        } else if (clientId.equals("client-3")) {

            // Sempre consulta o status atual do recurso escolhido antes de agir
            lockService.consultarStatus(resource);

            if (random.nextDouble() < 0.3) {
                lockService.tentarLiberarIndevidamente(resource, "client-2");
            }

            lockService.solicitarLock(resource);
        }
    }
}