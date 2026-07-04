package ru.axis.bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.axis.bot.config.AppConfig;
import ru.axis.bot.db.KnowledgeRepository;
import ru.axis.bot.db.ProfileRepository;
import ru.axis.bot.model.IncomingMessage;
import ru.axis.bot.model.KnowledgeEntry;
import ru.axis.bot.model.PlayerProfile;
import ru.axis.bot.model.VkUser;
import ru.axis.bot.util.DurationParser;
import ru.axis.bot.util.TextUtils;
import ru.axis.bot.vk.VkApiClient;

public final class MessageHandler {
    private static final List<String> PROFILE_FIELDS = List.of("name", "gender", "age", "spectrum", "index", "reputation", "note");
    private static final List<String> KNOWLEDGE_FIELDS = List.of("category", "title", "keywords", "content");
    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);
    private static final Pattern WHOSE_SPECTRUM = Pattern.compile("(?iu).*(?:какой|скажи)?\\s*спектр\\s+у\\s+(.+?)\\??$");
    private static final Pattern WHOSE_INDEX = Pattern.compile("(?iu).*(?:какой|скажи)?\\s*(?:индекс)\\s+у\\s+(.+?)\\??$");
    private static final Pattern WHOSE_REPUTATION = Pattern.compile("(?iu).*(?:какая|скажи)?\\s*репутац(?:ия|ию)\\s+у\\s+(.+?)\\??$");
    private static final Pattern DIRECT_SPECTRUM = Pattern.compile("(?iu)^(?:скажи\\s+)?спектр\\s+(?!у\\b)(.+?)\\??$");
    private static final Pattern DIRECT_INDEX = Pattern.compile("(?iu)^(?:скажи\\s+)?индекс\\s+(?!у\\b)(.+?)\\??$");
    private static final Pattern DIRECT_REPUTATION = Pattern.compile("(?iu)^(?:скажи\\s+)?репутац(?:ия|ию)\\s+(?!у\\b)(.+?)\\??$");
    private static final Pattern SET_REPUTATION = Pattern.compile("(?iu)^репутац(?:ия|ию)\\s+(.+?)\\s*=\\s*(.+)$");
    private static final Pattern NUMBER_ONLY = Pattern.compile("^\\d+$");
    private static final Pattern CHOOSE_NUMBER = Pattern.compile("(?iu)^выб(?:е|и)р(?:и|ать)?\\s+(\\d+)$");

    private final AppConfig config;
    private final VkApiClient vkApiClient;
    private final ProfileRepository profileRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeService knowledgeService;
    private final AdminService adminService;
    private final Map<String, PendingModerationAction> pendingModerationActions = new ConcurrentHashMap<>();
    private final Map<String, PendingDialog> pendingDialogs = new ConcurrentHashMap<>();

    public MessageHandler(
            AppConfig config,
            VkApiClient vkApiClient,
            ProfileRepository profileRepository,
            KnowledgeRepository knowledgeRepository,
            KnowledgeService knowledgeService,
            AdminService adminService
    ) {
        this.config = config;
        this.vkApiClient = vkApiClient;
        this.profileRepository = profileRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.knowledgeService = knowledgeService;
        this.adminService = adminService;
    }

    public void handle(IncomingMessage message) {
        try {
            String text = message.text() == null ? "" : message.text().trim();
            if (text.isBlank()) {
                return;
            }

            if (message.fromId() <= 0) {
                return;
            }

            adminService.markActive(message.fromId(), message.peerId());

            boolean addressed = !message.chat()
                    || isAddressedToBot(text)
                    || text.trim().startsWith("/admin")
                    || hasPendingDialog(message)
                    || hasPendingModeration(message);
            if (!addressed) {
                return;
            }

            String command = stripAddress(text, message.chat()).trim();
            if (command.isBlank()) {
                send(message.peerId(), helpMessage(adminService.isAdmin(message.fromId())));
                return;
            }

            String response = route(command, message);
            if (response != null && !response.isBlank()) {
                send(message.peerId(), response);
            }
        } catch (Exception exception) {
            log.error("Failed to process message from {}", message.fromId(), exception);
            send(message.peerId(), "Произошла ошибка при обработке сообщения. Проверьте формат команды или логи бота.");
        }
    }

    private String route(String command, IncomingMessage message) throws Exception {
        String normalized = TextUtils.normalize(command);

        if (hasPendingDialog(message)) {
            String dialogResult = tryResolvePendingDialog(command, message);
            if (dialogResult != null) {
                return dialogResult;
            }
        }

        if (hasPendingModeration(message)) {
            String selectionResult = tryResolvePendingModeration(command, message);
            if (selectionResult != null) {
                return selectionResult;
            }
        }

        if (normalized.equals("/admin") || normalized.equals("admin")) {
            return handleSlashAdmin("", message);
        }

        if (normalized.startsWith("/admin ")) {
            return handleSlashAdmin(command.substring(command.indexOf(' ') + 1).trim(), message);
        }

        if (normalized.equals("help") || normalized.equals("команды") || normalized.equals("помощь")) {
            return helpMessage(adminService.isAdmin(message.fromId()));
        }

        if (normalized.equals("ии")) {
            return handleDirectAiHelp(message);
        }

        if (normalized.startsWith("ии ")) {
            return handleDirectAi(command.substring(command.indexOf(' ') + 1).trim(), message);
        }

        if (normalized.equals("создать профиль")
                || normalized.equals("новый профиль")
                || normalized.equals("добавить профиль")) {
            return startProfileWizard(message, false);
        }

        if (normalized.equals("обновить профиль")
                || normalized.equals("изменить профиль")
                || normalized.equals("редактировать профиль")) {
            return startProfileWizard(message, true);
        }

        if (normalized.equals("добавить знание")
                || normalized.equals("создать знание")
                || normalized.equals("новое знание")) {
            return startKnowledgeWizard(message);
        }

        if (normalized.startsWith("админ ")) {
            return handleAdminCommand(command.substring(command.indexOf(' ') + 1).trim(), message);
        }

        if (normalized.startsWith("мут ")) {
            return handleMute(command.trim(), message);
        }

        if (normalized.startsWith("размут ")) {
            return handleUnmute(command.trim(), message);
        }

        if (normalized.equals("мой профиль")
                || normalized.equals("профиль мой")
                || normalized.equals("скажи мой профиль")
                || normalized.equals("покажи мой профиль")) {
            return renderOwnProfile(message.fromId());
        }

        if (normalized.equals("мой индекс")
                || normalized.equals("скажи мой индекс")
                || normalized.contains("какой у меня индекс")) {
            return renderOwnIndex(message.fromId());
        }

        if (normalized.equals("мой спектр")
                || normalized.equals("скажи мой спектр")
                || normalized.contains("какой у меня спектр")) {
            return renderOwnSpectrum(message.fromId());
        }

        if (normalized.equals("моя репутация")
                || normalized.equals("скажи мою репутацию")
                || normalized.contains("какая у меня репутация")) {
            return renderOwnReputation(message.fromId());
        }

        if (normalized.startsWith("профиль ")) {
            return renderNamedProfile(command.substring(command.indexOf(' ') + 1).trim());
        }

        Matcher setReputationMatcher = SET_REPUTATION.matcher(command);
        if (setReputationMatcher.matches()) {
            return handleSetReputation(setReputationMatcher.group(1), setReputationMatcher.group(2), message);
        }

        Matcher directSpectrumMatcher = DIRECT_SPECTRUM.matcher(command);
        if (directSpectrumMatcher.matches()) {
            return renderSpectrumForTarget(directSpectrumMatcher.group(1));
        }

        Matcher directIndexMatcher = DIRECT_INDEX.matcher(command);
        if (directIndexMatcher.matches()) {
            return renderIndexForTarget(directIndexMatcher.group(1));
        }

        Matcher directReputationMatcher = DIRECT_REPUTATION.matcher(command);
        if (directReputationMatcher.matches()) {
            return renderReputationForTarget(directReputationMatcher.group(1));
        }

        Matcher spectrumMatcher = WHOSE_SPECTRUM.matcher(command);
        if (spectrumMatcher.matches()) {
            return renderSpectrumForTarget(spectrumMatcher.group(1));
        }

        Matcher indexMatcher = WHOSE_INDEX.matcher(command);
        if (indexMatcher.matches()) {
            return renderIndexForTarget(indexMatcher.group(1));
        }

        Matcher reputationMatcher = WHOSE_REPUTATION.matcher(command);
        if (reputationMatcher.matches()) {
            return renderReputationForTarget(reputationMatcher.group(1));
        }

        if (normalized.startsWith("проверь пост")) {
            return handlePostCheck(command, message);
        }

        return knowledgeService.answerQuestion(command);
    }

    private String handleDirectAiHelp(IncomingMessage message) throws Exception {
        if (!adminService.isAdmin(message.fromId())) {
            return "Прямой ИИ-режим доступен только администраторам.";
        }
        return """
                Режим ИИ:
                - Аксис ИИ что можешь предложить по лору?
                - Аксис ИИ оцени идею: ...
                - Аксис ИИ ответь как Аксис: ...
                """.trim();
    }

    private String handleDirectAi(String prompt, IncomingMessage message) throws Exception {
        if (!adminService.isAdmin(message.fromId())) {
            return "Прямой ИИ-режим доступен только администраторам.";
        }
        if (prompt.isBlank()) {
            return handleDirectAiHelp(message);
        }
        return knowledgeService.answerWithDirectAi(prompt);
    }

    private String handleAdminCommand(String adminCommand, IncomingMessage message) throws Exception {
        if (!adminService.isAdmin(message.fromId())) {
            return "Эта команда доступна только администраторам.";
        }

        String normalized = TextUtils.normalize(adminCommand);
        if (normalized.equals("help") || normalized.equals("помощь") || normalized.equals("команды")) {
            return helpMessage(true);
        }

        if (normalized.equals("создать профиль")
                || normalized.equals("новый профиль")
                || normalized.equals("добавить профиль")) {
            return startProfileWizard(message, false);
        }

        if (normalized.equals("обновить профиль")
                || normalized.equals("изменить профиль")
                || normalized.equals("редактировать профиль")) {
            return startProfileWizard(message, true);
        }

        if (normalized.equals("добавить знание")
                || normalized.equals("создать знание")
                || normalized.equals("новое знание")) {
            return startKnowledgeWizard(message);
        }

        if (normalized.startsWith("профиль ")) {
            return handleAdminProfile(adminCommand.trim());
        }

        if (normalized.startsWith("знание ")) {
            return handleAdminKnowledge(adminCommand.trim(), message.fromId());
        }

        if (normalized.startsWith("мут ")) {
            return handleMute(adminCommand.trim(), message);
        }

        if (normalized.startsWith("размут ")) {
            return handleUnmute(adminCommand.trim(), message);
        }

        return "Не понял админ-команду. Напишите `Аксис help` и посмотрите блок администратора.";
    }

    private String handleSlashAdmin(String command, IncomingMessage message) throws Exception {
        if (!adminService.isAdmin(message.fromId())) {
            return "Команда /admin доступна только администраторам.";
        }

        String normalized = TextUtils.normalize(command);
        if (normalized.isBlank()) {
            return adminService.renderPanel();
        }
        if (normalized.equals("list")) {
            return adminService.renderAdminList();
        }
        if (normalized.startsWith("add ")) {
            long targetUserId = resolveUserId(command.substring(command.indexOf(' ') + 1).trim());
            return adminService.addAdmin(targetUserId, message.fromId());
        }
        if (normalized.startsWith("remove ")) {
            long targetUserId = resolveUserId(command.substring(command.indexOf(' ') + 1).trim());
            return adminService.removeAdmin(targetUserId);
        }
        return """
                Команда /admin поддерживает:
                /admin
                /admin list
                /admin add id123456
                /admin remove id123456
                """.trim();
    }

    private String handleAdminProfile(String payload) throws Exception {
        String[] parts = payload.split("\\s*;\\s*");
        String head = parts[0].trim();
        Map<String, String> params = parseParams(parts);

        String normalizedHead = TextUtils.normalize(head);
        String userRef = firstNonBlank(
                params.get("пользователь"),
                params.get("user"),
                params.get("vk"),
                params.get("игрок")
        );
        if (userRef == null || userRef.isBlank()) {
            return "Для профиля нужен параметр `пользователь=...`.";
        }

        long userId = resolveUserId(userRef);
        PlayerProfile profile = profileRepository.findByUserId(userId).orElseGet(() -> {
            PlayerProfile fresh = new PlayerProfile();
            fresh.setVkUserId(userId);
            fresh.setVkProfileUrl("https://vk.com/id" + userId);
            return fresh;
        });

        if (normalizedHead.equals("профиль get")) {
            return renderProfile(profile);
        }

        if (normalizedHead.equals("профиль set") || normalizedHead.equals("профиль update")) {
            profile.setVkProfileUrl(firstNonBlank(params.get("ссылка"), params.get("link"), profile.getVkProfileUrl()));
            profile.setCharacterName(firstNonBlank(params.get("имя"), params.get("name"), profile.getCharacterName()));
            profile.setCharacterGender(firstNonBlank(params.get("пол"), params.get("gender"), profile.getCharacterGender()));
            profile.setCharacterAge(firstNonBlank(params.get("возраст"), params.get("age"), profile.getCharacterAge()));
            profile.setSpectrum(firstNonBlank(params.get("спектр"), params.get("spectrum"), profile.getSpectrum()));
            profile.setCharacterIndex(firstNonBlank(params.get("индекс"), params.get("index"), profile.getCharacterIndex()));
            profile.setReputation(firstNonBlank(params.get("репутация"), params.get("reputation"), profile.getReputation()));
            profile.setNote(firstNonBlank(params.get("заметка"), params.get("note"), profile.getNote()));
            profileRepository.upsert(profile);
            return "Профиль сохранён.\n\n" + renderProfile(profile);
        }

        return "Не понял команду профиля. Используйте `профиль set`, `профиль update` или `профиль get`.";
    }

    private String handleAdminKnowledge(String payload, long adminId) throws Exception {
        String[] parts = payload.split("\\s*;\\s*");
        String head = parts[0].trim();
        Map<String, String> params = parseParams(parts);
        String normalizedHead = TextUtils.normalize(head);

        if (normalizedHead.equals("знание add")) {
            String category = firstNonBlank(params.get("категория"), params.get("category"));
            String title = firstNonBlank(params.get("заголовок"), params.get("title"));
            String content = firstNonBlank(params.get("текст"), params.get("text"), params.get("content"));
            String keywords = firstNonBlank(params.get("ключи"), params.get("keywords"), "");

            if (category == null || title == null || content == null) {
                return "Для добавления знания нужны `категория=...`, `заголовок=...`, `текст=...`.";
            }

            KnowledgeEntry entry = new KnowledgeEntry();
            entry.setCategory(category);
            entry.setTitle(title);
            entry.setKeywords(keywords);
            entry.setContent(content);
            entry.setCreatedBy(adminId);
            long id = knowledgeRepository.add(entry);
            return "Запись добавлена в базу знаний. ID=" + id;
        }

        if (normalizedHead.equals("знание delete")) {
            String idValue = firstNonBlank(params.get("id"), params.get("номер"));
            if (idValue == null) {
                return "Для удаления знания нужен `id=...`.";
            }
            boolean deleted = knowledgeRepository.delete(Long.parseLong(idValue.trim()));
            return deleted ? "Запись удалена." : "Запись с таким ID не найдена.";
        }

        return "Не понял команду базы знаний. Используйте `знание add` или `знание delete`.";
    }

    private String handleMute(String payload, IncomingMessage message) throws Exception {
        if (!adminService.isAdmin(message.fromId())) {
            return "Команда мута доступна только администраторам.";
        }
        if (!message.chat()) {
            return "Мут можно выдать только в беседе.";
        }

        ModerationRequest request = parseMuteRequest(payload);
        if (request.target() == null || request.target().isBlank()) {
            return "Напишите, кого мутить. Например: `Аксис мут Иван 30м`.";
        }

        ModerationResolution resolution = resolveModerationTarget(request.target(), message.peerId());
        if (resolution.options() != null) {
            pendingModerationActions.put(
                    pendingKey(message),
                    new PendingModerationAction("mute", request.durationRaw(), request.reason(), resolution.options())
            );
            return resolution.message();
        }
        if (resolution.message() != null) {
            return resolution.message();
        }

        long userId = resolution.userId();
        long seconds = DurationParser.parseSeconds(request.durationRaw());
        String reason = request.reason();

        vkApiClient.changeConversationMemberRestrictions(message.peerId(), userId, "ro", seconds);
        return "Ограничение выдано пользователю id" + userId + " на " + request.durationRaw() + ". Причина: " + reason;
    }

    private String handleUnmute(String payload, IncomingMessage message) throws Exception {
        if (!adminService.isAdmin(message.fromId())) {
            return "Команда размута доступна только администраторам.";
        }
        if (!message.chat()) {
            return "Размут работает только в беседе.";
        }

        String target = parseUnmuteTarget(payload);
        if (target == null || target.isBlank()) {
            return "Напишите, кого размутить. Например: `Аксис размут Иван`.";
        }

        ModerationResolution resolution = resolveModerationTarget(target, message.peerId());
        if (resolution.options() != null) {
            pendingModerationActions.put(
                    pendingKey(message),
                    new PendingModerationAction("unmute", null, null, resolution.options())
            );
            return resolution.message();
        }
        if (resolution.message() != null) {
            return resolution.message();
        }

        long userId = resolution.userId();
        vkApiClient.changeConversationMemberRestrictions(message.peerId(), userId, "rw", null);
        return "Ограничение снято с пользователя id" + userId + ".";
    }

    private String handlePostCheck(String command, IncomingMessage message) throws Exception {
        String postText = command.replaceFirst("(?iu)^проверь\\s+пост\\s*:?\\s*", "").trim();
        if (postText.isBlank() && message.replyText() != null) {
            postText = message.replyText().trim();
        }
        if (postText.isBlank() && message.forwardedText() != null) {
            postText = message.forwardedText().trim();
        }
        if (postText.isBlank()) {
            return "После команды `проверь пост` нужен текст поста, ответ на сообщение или пересланный пост.";
        }
        return knowledgeService.moderatePost(postText);
    }

    private String renderOwnProfile(long userId) throws SQLException {
        return profileRepository.findByUserId(userId)
                .map(this::renderProfile)
                .orElse("Ваш профиль пока не заполнен. Попросите администратора добавить его в базу.");
    }

    private String renderOwnIndex(long userId) throws SQLException {
        return profileRepository.findByUserId(userId)
                .map(profile -> fieldAnswer("индекс", profile.getCharacterName(), profile.getCharacterIndex()))
                .orElse("Ваш профиль пока не найден. Попросите администратора добавить индекс.");
    }

    private String renderOwnSpectrum(long userId) throws SQLException {
        return profileRepository.findByUserId(userId)
                .map(profile -> fieldAnswer("спектр", profile.getCharacterName(), profile.getSpectrum()))
                .orElse("Ваш профиль пока не найден. Попросите администратора добавить спектр.");
    }

    private String renderOwnReputation(long userId) throws SQLException {
        return profileRepository.findByUserId(userId)
                .map(profile -> fieldAnswer("репутация", profile.getCharacterName(), profile.getReputation()))
                .orElse("Ваш профиль пока не найден. Попросите администратора добавить репутацию.");
    }

    private String renderNamedProfile(String target) throws Exception {
        return resolveProfile(target)
                .map(this::renderProfile)
                .orElse("Не нашёл профиль по этому запросу.");
    }

    private String renderSpectrumForTarget(String target) throws Exception {
        return resolveProfile(target)
                .map(profile -> fieldAnswer("спектр", profile.getCharacterName(), profile.getSpectrum()))
                .orElse("Не нашёл профиль, чтобы назвать спектр.");
    }

    private String renderIndexForTarget(String target) throws Exception {
        return resolveProfile(target)
                .map(profile -> fieldAnswer("индекс", profile.getCharacterName(), profile.getCharacterIndex()))
                .orElse("Не нашёл профиль, чтобы назвать индекс.");
    }

    private String renderReputationForTarget(String target) throws Exception {
        return resolveProfile(target)
                .map(profile -> fieldAnswer("репутация", profile.getCharacterName(), profile.getReputation()))
                .orElse("Не нашёл профиль, чтобы назвать репутацию.");
    }

    private String handleSetReputation(String target, String reputationValue, IncomingMessage message) throws Exception {
        if (!adminService.isAdmin(message.fromId())) {
            return renderReputationForTarget(target);
        }

        Optional<PlayerProfile> existingProfile = resolveProfile(target);
        if (existingProfile.isEmpty()) {
            return "Не нашёл профиль для обновления репутации.";
        }

        PlayerProfile profile = existingProfile.get();
        profile.setReputation(reputationValue.trim());
        profileRepository.upsert(profile);
        String subject = profile.getCharacterName() == null || profile.getCharacterName().isBlank()
                ? "Игроку"
                : "Персонажу " + profile.getCharacterName();
        return subject + " установлена репутация: " + profile.getReputation();
    }

    private Optional<PlayerProfile> resolveProfile(String rawTarget) throws Exception {
        String target = rawTarget.trim();
        Long numericId = TextUtils.tryParseVkUserId(target);
        if (numericId != null) {
            return profileRepository.findByUserId(numericId);
        }

        Long resolvedUserId = resolveUserIdQuietly(target);
        if (resolvedUserId != null) {
            return profileRepository.findByUserId(resolvedUserId);
        }

        Optional<PlayerProfile> exact = profileRepository.findByCharacterName(target);
        if (exact.isPresent()) {
            return exact;
        }

        List<PlayerProfile> candidates = profileRepository.searchByCharacterName(target, 1);
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.getFirst());
    }

    private long resolveUserId(String reference) throws Exception {
        Long local = TextUtils.tryParseVkUserId(reference);
        if (local != null) {
            return local;
        }

        JsonNode node = vkApiClient.resolveScreenName(reference);
        if (node != null && "user".equalsIgnoreCase(node.path("type").asText())) {
            return node.path("object_id").asLong();
        }

        throw new IllegalArgumentException("Не удалось определить VK ID по ссылке или упоминанию: " + reference);
    }

    private Long resolveUserIdQuietly(String reference) {
        try {
            return resolveUserId(reference);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String renderProfile(PlayerProfile profile) {
        return """
                Профиль игрока:
                VK: %s
                Имя персонажа: %s
                Пол: %s
                Возраст: %s
                Спектр: %s
                Индекс: %s
                Репутация: %s
                Заметка: %s
                """.formatted(
                safe(profile.getVkProfileUrl()),
                safe(profile.getCharacterName()),
                safe(profile.getCharacterGender()),
                safe(profile.getCharacterAge()),
                safe(profile.getSpectrum()),
                safe(profile.getCharacterIndex()),
                safe(profile.getReputation()),
                safe(profile.getNote())
        ).trim();
    }

    private String fieldAnswer(String fieldName, String characterName, String value) {
        if (value == null || value.isBlank()) {
            return "В профиле пока не заполнено поле `" + fieldName + "`.";
        }
        String subject = characterName == null || characterName.isBlank() ? "У игрока" : "У персонажа " + characterName;
        return subject + " " + fieldName + ": " + value;
    }

    private String helpMessage(boolean admin) {
        String userHelp = """
                Команды Аксис

                Профиль:
                - Аксис мой профиль
                - Аксис мой спектр
                - Аксис мой индекс
                - Аксис моя репутация

                Другие игроки:
                - Аксис профиль <имя>
                - Аксис спектр <имя>
                - Аксис индекс <имя>
                - Аксис репутация <имя>

                Лор и правила:
                - Аксис, какой размер постов актива?
                - Аксис, какие правила для анкет?
                - Аксис, что известно о персонаже или локации?

                Проверка поста:
                - Аксис проверь пост: <текст>
                - Аксис проверь пост

                Служебное:
                - !Аксис
                - Аксис help
                """;

        if (!admin) {
            return userHelp;
        }

        return userHelp + """

                Админка:
                - Аксис создать профиль
                - Аксис обновить профиль
                - Аксис добавить знание

                Быстрые изменения:
                - Аксис репутация <имя> = <значение>
                - Аксис ИИ <вопрос>

                Модерация:
                - Аксис мут <имя>
                - Аксис мут <имя> 30м
                - Аксис размут <имя>
                - Аксис выбрать 2

                Управление админами:
                - /admin
                - /admin list
                - /admin add id123456
                - /admin remove id123456

                Технические команды:
                - Аксис админ профиль set ; пользователь=id123 ; имя=... ; пол=... ; возраст=... ; спектр=... ; индекс=... ; репутация=... ; заметка=...
                - Аксис админ профиль update ; пользователь=id123 ; спектр=... ; индекс=... ; репутация=...
                - Аксис админ профиль get ; пользователь=id123
                - Аксис админ знание add ; категория=... ; заголовок=... ; ключи=... ; текст=...
                - Аксис админ знание delete ; id=1
                - Аксис админ мут ; пользователь=Иван ; время=30м ; причина=...
                - Аксис админ размут ; пользователь=Иван
                """;
    }

    private boolean isAddressedToBot(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String botName = config.vkBotName().toLowerCase(Locale.ROOT);
        return lower.startsWith(botName)
                || lower.startsWith("!" + botName)
                || lower.startsWith("/axis")
                || lower.startsWith("!axis")
                || lower.startsWith("axis")
                || lower.matches("^\\[club\\d+\\|[^\\]]*]\\s*.*");
    }

    private String stripAddress(String text, boolean chat) {
        String result = text.trim();
        result = result.replaceFirst("(?iu)^\\[club\\d+\\|[^\\]]*]\\s*", "");
        result = result.replaceFirst("(?iu)^!" + Pattern.quote(config.vkBotName()) + "[\\s,!:.-]*", "");
        result = result.replaceFirst("(?iu)^" + Pattern.quote(config.vkBotName()) + "[\\s,!:.-]*", "");
        result = result.replaceFirst("(?iu)^!axis[\\s,!:.-]*", "");
        result = result.replaceFirst("(?iu)^/axis[\\s,!:.-]*", "");
        result = result.replaceFirst("(?iu)^axis[\\s,!:.-]*", "");
        return result.trim();
    }

    private Map<String, String> parseParams(String[] parts) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int index = 1; index < parts.length; index++) {
            String chunk = parts[index].trim();
            int delimiterIndex = chunk.indexOf('=');
            if (delimiterIndex <= 0) {
                continue;
            }
            String key = TextUtils.normalize(chunk.substring(0, delimiterIndex));
            String value = chunk.substring(delimiterIndex + 1).trim();
            params.put(key, value);
        }
        return params;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "не указано" : value;
    }

    private boolean hasPendingModeration(IncomingMessage message) {
        return pendingModerationActions.containsKey(pendingKey(message));
    }

    private boolean hasPendingDialog(IncomingMessage message) {
        return pendingDialogs.containsKey(pendingKey(message));
    }

    private String tryResolvePendingDialog(String command, IncomingMessage message) throws Exception {
        PendingDialog dialog = pendingDialogs.get(pendingKey(message));
        if (dialog == null) {
            return null;
        }

        String normalized = TextUtils.normalize(command);
        if (normalized.equals("отмена") || normalized.equals("cancel")) {
            pendingDialogs.remove(pendingKey(message));
            return "Действие отменено.";
        }

        return switch (dialog.type()) {
            case PROFILE -> continueProfileWizard(dialog, command, message);
            case KNOWLEDGE -> continueKnowledgeWizard(dialog, command, message);
        };
    }

    private String tryResolvePendingModeration(String command, IncomingMessage message) throws Exception {
        PendingModerationAction pendingAction = pendingModerationActions.get(pendingKey(message));
        if (pendingAction == null) {
            return null;
        }

        String normalized = TextUtils.normalize(command);
        if (normalized.equals("отмена") || normalized.equals("cancel")) {
            pendingModerationActions.remove(pendingKey(message));
            return "Выбор отменён.";
        }

        Integer selectedIndex = parseSelectionIndex(command.trim());
        if (selectedIndex == null) {
            return "Уточните номер варианта. Например: `Аксис выбрать 2`.";
        }
        if (selectedIndex < 1 || selectedIndex > pendingAction.options().size()) {
            return "Такого номера нет. Выберите вариант от 1 до " + pendingAction.options().size() + ".";
        }

        PendingModerationOption option = pendingAction.options().get(selectedIndex - 1);
        pendingModerationActions.remove(pendingKey(message));

        if (pendingAction.action().equals("mute")) {
            long seconds = DurationParser.parseSeconds(pendingAction.durationRaw());
            vkApiClient.changeConversationMemberRestrictions(message.peerId(), option.userId(), "ro", seconds);
            return "Ограничение выдано пользователю id" + option.userId()
                    + " (" + option.label() + ") на " + pendingAction.durationRaw()
                    + ". Причина: " + pendingAction.reason();
        }

        vkApiClient.changeConversationMemberRestrictions(message.peerId(), option.userId(), "rw", null);
        return "Ограничение снято с пользователя id" + option.userId() + " (" + option.label() + ").";
    }

    private ModerationRequest parseMuteRequest(String payload) {
        String normalized = TextUtils.normalize(payload);
        if (!payload.contains(";")) {
            String raw = payload.replaceFirst("(?iu)^(?:админ\\s+)?мут\\s+", "").trim();
            String[] tokens = raw.split("\\s+");
            String durationRaw = "30м";
            if (tokens.length >= 2) {
                String lastToken = tokens[tokens.length - 1];
                if (looksLikeDuration(lastToken)) {
                    durationRaw = lastToken;
                    raw = raw.substring(0, raw.length() - lastToken.length()).trim();
                }
            }
            return new ModerationRequest(raw, durationRaw, "не указана", normalized);
        }

        Map<String, String> params = parseParams(payload.split("\\s*;\\s*"));
        String target = firstNonBlank(params.get("пользователь"), params.get("user"), params.get("vk"));
        String durationRaw = firstNonBlank(params.get("время"), params.get("time"), "30м");
        String reason = firstNonBlank(params.get("причина"), params.get("reason"), "не указана");
        return new ModerationRequest(target, durationRaw, reason, normalized);
    }

    private String parseUnmuteTarget(String payload) {
        if (!payload.contains(";")) {
            return payload.replaceFirst("(?iu)^(?:админ\\s+)?размут\\s+", "").trim();
        }
        Map<String, String> params = parseParams(payload.split("\\s*;\\s*"));
        return firstNonBlank(params.get("пользователь"), params.get("user"), params.get("vk"));
    }

    private ModerationResolution resolveModerationTarget(String target, long peerId) throws Exception {
        Long explicitUserId = TextUtils.tryParseVkUserId(target);
        if (explicitUserId != null) {
            return new ModerationResolution(explicitUserId, null, null);
        }

        Long resolvedUserId = resolveUserIdQuietly(target);
        if (resolvedUserId != null) {
            return new ModerationResolution(resolvedUserId, null, null);
        }

        List<PendingModerationOption> options = new ArrayList<>(adminService.findActiveUsersByName(peerId, target, 10).stream()
                .map(user -> new PendingModerationOption(user.id(), user.displayName()))
                .toList());

        options = options.stream()
                .sorted(Comparator.comparing(PendingModerationOption::label))
                .toList();

        if (options.isEmpty()) {
            return new ModerationResolution(null, "Не нашёл активного пользователя с таким именем в этой беседе.", null);
        }

        if (options.size() == 1) {
            return new ModerationResolution(options.getFirst().userId(), null, null);
        }

        return new ModerationResolution(null, renderAmbiguousModerationPrompt(options), options);
    }

    private String renderAmbiguousModerationPrompt(List<PendingModerationOption> options) {
        StringBuilder builder = new StringBuilder("Нашёл несколько пользователей. Кого выбрать?\n");
        for (int index = 0; index < options.size(); index++) {
            PendingModerationOption option = options.get(index);
            builder.append(index + 1)
                    .append(". ")
                    .append(option.label())
                    .append(" (id")
                    .append(option.userId())
                    .append(")\n");
        }
        builder.append("\nОтветьте номером или командой `Аксис выбрать 2`.");
        return builder.toString().trim();
    }

    private Integer parseSelectionIndex(String command) {
        String trimmed = command.trim();
        if (NUMBER_ONLY.matcher(trimmed).matches()) {
            return Integer.parseInt(trimmed);
        }

        Matcher matcher = CHOOSE_NUMBER.matcher(TextUtils.normalize(trimmed));
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private boolean looksLikeDuration(String value) {
        try {
            DurationParser.parseSeconds(value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String pendingKey(IncomingMessage message) {
        return message.peerId() + ":" + message.fromId();
    }

    private String startProfileWizard(IncomingMessage message, boolean updateMode) throws Exception {
        if (!adminService.isAdmin(message.fromId())) {
            return "Эта команда доступна только администраторам.";
        }

        PendingDialog dialog = new PendingDialog(DialogType.PROFILE, 0, new LinkedHashMap<>(), message.fromId(), updateMode);
        pendingDialogs.put(pendingKey(message), dialog);
        return """
                Начинаем %s.
                Пришлите ссылку на VK, `id123456` или упоминание игрока.
                Для отмены напишите `отмена`.
                """.formatted(updateMode ? "обновление профиля" : "создание профиля").trim();
    }

    private String continueProfileWizard(PendingDialog dialog, String command, IncomingMessage message) throws Exception {
        Map<String, String> values = dialog.values();
        String input = command.trim();

        if (dialog.stepIndex() == 0) {
            long userId = resolveUserId(input);
            PlayerProfile profile = profileRepository.findByUserId(userId).orElseGet(() -> {
                PlayerProfile fresh = new PlayerProfile();
                fresh.setVkUserId(userId);
                fresh.setVkProfileUrl("https://vk.com/id" + userId);
                return fresh;
            });

            values.put("userId", String.valueOf(userId));
            values.put("profileUrl", safe(profile.getVkProfileUrl()).equals("не указано") ? "https://vk.com/id" + userId : profile.getVkProfileUrl());
            values.put("name", nullToEmpty(profile.getCharacterName()));
            values.put("gender", nullToEmpty(profile.getCharacterGender()));
            values.put("age", nullToEmpty(profile.getCharacterAge()));
            values.put("spectrum", nullToEmpty(profile.getSpectrum()));
            values.put("index", nullToEmpty(profile.getCharacterIndex()));
            values.put("reputation", nullToEmpty(profile.getReputation()));
            values.put("note", nullToEmpty(profile.getNote()));

            pendingDialogs.put(pendingKey(message), dialog.nextStep());
            return profileFieldPrompt(values, 1, dialog.updateMode());
        }

        int fieldIndex = dialog.stepIndex() - 1;
        String field = PROFILE_FIELDS.get(fieldIndex);
        if (!isSkipValue(input)) {
            values.put(field, input);
        }

        if (dialog.stepIndex() >= PROFILE_FIELDS.size()) {
            PlayerProfile profile = new PlayerProfile();
            profile.setVkUserId(Long.parseLong(values.get("userId")));
            profile.setVkProfileUrl(values.get("profileUrl"));
            profile.setCharacterName(blankToNull(values.get("name")));
            profile.setCharacterGender(blankToNull(values.get("gender")));
            profile.setCharacterAge(blankToNull(values.get("age")));
            profile.setSpectrum(blankToNull(values.get("spectrum")));
            profile.setCharacterIndex(blankToNull(values.get("index")));
            profile.setReputation(blankToNull(values.get("reputation")));
            profile.setNote(blankToNull(values.get("note")));
            profileRepository.upsert(profile);
            pendingDialogs.remove(pendingKey(message));
            return "Профиль сохранён.\n\n" + renderProfile(profile);
        }

        PendingDialog nextDialog = dialog.nextStep();
        pendingDialogs.put(pendingKey(message), nextDialog);
        return profileFieldPrompt(values, nextDialog.stepIndex(), dialog.updateMode());
    }

    private String profileFieldPrompt(Map<String, String> values, int stepIndex, boolean updateMode) {
        String field = PROFILE_FIELDS.get(stepIndex - 1);
        String current = switch (field) {
            case "name" -> values.get("name");
            case "gender" -> values.get("gender");
            case "age" -> values.get("age");
            case "spectrum" -> values.get("spectrum");
            case "index" -> values.get("index");
            case "reputation" -> values.get("reputation");
            case "note" -> values.get("note");
            default -> "";
        };

        String prompt = switch (field) {
            case "name" -> "Введите имя персонажа:";
            case "gender" -> "Введите пол:";
            case "age" -> "Введите возраст:";
            case "spectrum" -> "Введите спектр:";
            case "index" -> "Введите индекс:";
            case "reputation" -> "Введите репутацию:";
            case "note" -> "Введите заметку:";
            default -> "Введите значение:";
        };

        if (updateMode || (current != null && !current.isBlank())) {
            return prompt + "\nТекущее значение: " + safe(current) + "\nМожно отправить `-`, чтобы оставить как есть.";
        }
        return prompt + "\nМожно отправить `-`, если поле пока пустое.";
    }

    private String startKnowledgeWizard(IncomingMessage message) throws Exception {
        if (!adminService.isAdmin(message.fromId())) {
            return "Эта команда доступна только администраторам.";
        }

        PendingDialog dialog = new PendingDialog(DialogType.KNOWLEDGE, 0, new LinkedHashMap<>(), message.fromId(), false);
        pendingDialogs.put(pendingKey(message), dialog);
        return """
                Начинаем добавление знания.
                Введите категорию, например: правила, лор, сюжет, персонажи, локации.
                Для отмены напишите `отмена`.
                """.trim();
    }

    private String continueKnowledgeWizard(PendingDialog dialog, String command, IncomingMessage message) throws Exception {
        Map<String, String> values = dialog.values();
        String input = command.trim();

        String field = KNOWLEDGE_FIELDS.get(dialog.stepIndex());
        values.put(field, isSkipValue(input) ? "" : input);

        if (dialog.stepIndex() >= KNOWLEDGE_FIELDS.size() - 1) {
            String category = blankToNull(values.get("category"));
            String title = blankToNull(values.get("title"));
            String content = blankToNull(values.get("content"));
            if (category == null || title == null || content == null) {
                pendingDialogs.remove(pendingKey(message));
                return "Не удалось сохранить знание: категория, заголовок и текст обязательны.";
            }

            KnowledgeEntry entry = new KnowledgeEntry();
            entry.setCategory(category);
            entry.setTitle(title);
            entry.setKeywords(blankToNull(values.get("keywords")));
            entry.setContent(content);
            entry.setCreatedBy(message.fromId());
            long id = knowledgeRepository.add(entry);
            pendingDialogs.remove(pendingKey(message));
            return "Запись добавлена в базу знаний. ID=" + id;
        }

        PendingDialog nextDialog = dialog.nextStep();
        pendingDialogs.put(pendingKey(message), nextDialog);
        return knowledgeFieldPrompt(nextDialog.stepIndex());
    }

    private String knowledgeFieldPrompt(int stepIndex) {
        return switch (KNOWLEDGE_FIELDS.get(stepIndex)) {
            case "title" -> "Введите заголовок записи:";
            case "keywords" -> "Введите ключевые слова через запятую или `-`, если не нужны:";
            case "content" -> "Введите текст записи:";
            default -> "Введите значение:";
        };
    }

    private boolean isSkipValue(String value) {
        String normalized = TextUtils.normalize(value);
        return normalized.equals("-") || normalized.equals("пропустить") || normalized.equals("skip");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void send(long peerId, String message) {
        try {
            vkApiClient.sendMessage(peerId, message);
        } catch (Exception exception) {
            log.error("Failed to send VK message to peer {}", peerId, exception);
        }
    }

    private record ModerationRequest(String target, String durationRaw, String reason, String normalizedPayload) {
    }

    private record ModerationResolution(Long userId, String message, List<PendingModerationOption> options) {
    }

    private record PendingModerationAction(String action, String durationRaw, String reason, List<PendingModerationOption> options) {
    }

    private record PendingModerationOption(long userId, String label) {
    }

    private enum DialogType {
        PROFILE,
        KNOWLEDGE
    }

    private record PendingDialog(
            DialogType type,
            int stepIndex,
            Map<String, String> values,
            long startedBy,
            boolean updateMode
    ) {
        private PendingDialog nextStep() {
            return new PendingDialog(type, stepIndex + 1, values, startedBy, updateMode);
        }
    }
}
