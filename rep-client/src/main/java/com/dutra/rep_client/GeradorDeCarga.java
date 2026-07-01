package com.dutra.rep_client;

import com.dutra.rep_client.service.ClientLockService;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class GeradorDeCarga {

    private final ClientLockService lockService;
    private final String clientId = System.getenv("CLIENT_ID");

    public GeradorDeCarga(ClientLockService lockService) {
        this.lockService = lockService;
    }

    // Usamos initialDelay para evitar o log duplicado na subida da aplicação
    @Scheduled(initialDelay = 5000, fixedDelay = 15000)
    public void executarCarga() {
        String resource = "dataset-X";

        if (clientId.equals("client-2") && Math.random() < 0.6) {
            // Client 2 é instável
            lockService.solicitarLockComFalhaSimulada(resource);

        } else if (clientId.equals("client-3") && Math.random() < 0.4) {
            // Client 3 tenta agir maliciosamente
            lockService.tentarLiberarIndevidamente(resource, "client-1");
            lockService.solicitarLock(resource);

        } else {
            // Fluxo normal
            lockService.solicitarLock(resource);
        }
    }
}