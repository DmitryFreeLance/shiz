package ru.axis.bot;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.axis.bot.ai.KieAiClient;
import ru.axis.bot.config.AppConfig;
import ru.axis.bot.db.Database;
import ru.axis.bot.db.KnowledgeRepository;
import ru.axis.bot.db.ProfileRepository;
import ru.axis.bot.db.ActivityRepository;
import ru.axis.bot.db.AdminRepository;
import ru.axis.bot.service.AdminService;
import ru.axis.bot.service.KnowledgeService;
import ru.axis.bot.service.MessageHandler;
import ru.axis.bot.vk.VkApiClient;
import ru.axis.bot.vk.VkLongPollRunner;

public final class AxisBotApplication {
    private static final Logger log = LoggerFactory.getLogger(AxisBotApplication.class);

    private AxisBotApplication() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        Files.createDirectories(config.dataDir());

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        Database database = new Database(config.dataDir().resolve("axis.db"));
        database.init();

        ProfileRepository profileRepository = new ProfileRepository(database);
        KnowledgeRepository knowledgeRepository = new KnowledgeRepository(database);
        VkApiClient vkApiClient = new VkApiClient(httpClient, config);
        ActivityRepository activityRepository = new ActivityRepository(database);
        AdminRepository adminRepository = new AdminRepository(database);
        AdminService adminService = new AdminService(config, adminRepository, activityRepository, vkApiClient);
        KieAiClient kieAiClient = new KieAiClient(httpClient, config);
        KnowledgeService knowledgeService = new KnowledgeService(knowledgeRepository, kieAiClient, config);
        MessageHandler messageHandler = new MessageHandler(
                config,
                vkApiClient,
                profileRepository,
                knowledgeRepository,
                knowledgeService,
                adminService
        );

        log.info("Axis bot started. Group ID={}, admins={}, AI enabled={}",
                config.vkGroupId(),
                config.adminIds(),
                config.aiEnabled());

        new VkLongPollRunner(vkApiClient, config, messageHandler).runForever();
    }
}
