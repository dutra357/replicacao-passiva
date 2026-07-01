package com.dutra.rep_client;

import com.dutra.rep_client.service.ClientLockService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class GeradorDeCarga {

    private final ClientLockService lockService;

    public GeradorDeCarga(ClientLockService lockService) {
        this.lockService = lockService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void iniciarImediatamente() {
        System.out.println("🚀 [CLIENTE] Inicializado. Iniciando requisições...");
        executarCarga();
    }

    @Scheduled(fixedDelay = 7000)
    public void executarCarga() {

        lockService.solicitarLock("arquivo-1");
    }
}