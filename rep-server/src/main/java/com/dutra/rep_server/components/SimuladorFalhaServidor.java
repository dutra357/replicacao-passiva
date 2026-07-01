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

    // Dispara após 30s de sistema no ar
    @Scheduled(initialDelay = 30000, fixedDelay = 120000)
    public void simularQuedaERecuperacao() {

        // Se a variável por algum motivo for nula, ou NÃO for o server-1, aborta silenciosamente.
        // O trim() remove espaços extras e equalsIgnoreCase ignora maiúsculas/minúsculas.
        if (myServerId == null || !myServerId.trim().equalsIgnoreCase("server-1")) {
            return;
        }

        System.out.printf("💥💥💥 [%s] MODO CAOS: Partição de rede! O servidor ficará inoperante por 10s...%n", myServerId);
        lockService.setCrashed(true); // Isola o servidor logicamente bloqueando o Controller

        try {
            Thread.sleep(10000); // 10 segundos de indisponibilidade
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.printf("🔌 [%s] Rede restabelecida. Buscando estado autoritativo do cluster...%n", myServerId);

        boolean recuperado = false;
        // Pede os locks atuais para os outros servidores sobreviventes (server-2 ou server-3)
        for (String node : servers) {
            if (!node.contains(myServerId)) {
                try {
                    Map<String, String> activeLocks = restClient.get()
                            .uri(node + "/api/lock/state")
                            .retrieve()
                            .body(new ParameterizedTypeReference<Map<String, String>>() {});

                    if (activeLocks != null) {
                        lockService.importState(activeLocks);
                        System.out.printf("✅ [%s] Estado sincronizado a partir de %s.%n", myServerId, node);
                        recuperado = true;
                        break; // Sucesso! Não precisa pedir pro próximo.
                    }
                } catch (Exception e) {
                    System.out.printf("⚠️ [%s] Falha ao tentar extrair estado do %s.%n", myServerId, node);
                }
            }
        }

        if (!recuperado) {
            System.out.printf("⚠️ [%s] Nenhum par vivo encontrado. Iniciando memória limpa.%n", myServerId);
        }

        lockService.setCrashed(false); // Libera as rotas REST para voltar a operar
        System.out.printf("🛡️ [%s] Recuperação finalizada. Atuando como BACKUP (disponível).%n", myServerId);
    }
}