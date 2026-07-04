package ru.axis.bot.service;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import ru.axis.bot.ai.KieAiClient;
import ru.axis.bot.config.AppConfig;
import ru.axis.bot.db.KnowledgeRepository;
import ru.axis.bot.model.KnowledgeEntry;
import ru.axis.bot.model.KnowledgeHit;
import ru.axis.bot.util.TextUtils;

public final class KnowledgeService {
    private static final Set<String> MODERATION_CATEGORIES = Set.of("правила", "лор", "сюжет");

    private final KnowledgeRepository knowledgeRepository;
    private final KieAiClient kieAiClient;
    private final AppConfig config;

    public KnowledgeService(KnowledgeRepository knowledgeRepository, KieAiClient kieAiClient, AppConfig config) {
        this.knowledgeRepository = knowledgeRepository;
        this.kieAiClient = kieAiClient;
        this.config = config;
    }

    public String answerQuestion(String question) throws SQLException {
        List<KnowledgeHit> hits = search(question, 5);
        if (hits.isEmpty()) {
            return "Пока не нашёл ответа в базе знаний. Попросите администратора добавить эту информацию.";
        }

        KnowledgeHit bestHit = hits.getFirst();
        if (!config.aiConfigured() || bestHit.score() >= 18) {
            return renderDirectAnswer(hits);
        }

        String context = renderContext(hits);
        String prompt = """
                Вопрос игрока:
                %s

                Контекст проекта:
                %s

                Ответь по-русски. Используй только контекст выше.
                Если данных недостаточно, честно скажи, что ответа пока нет в базе.
                Держи ответ кратким и полезным.
                """.formatted(question, context);

        String answer = kieAiClient.chat(
                """
                Ты Аксис, помощник ролевого проекта VK.
                Нельзя придумывать факты вне контекста.
                """,
                prompt
        );
        return answer.isBlank() ? renderDirectAnswer(hits) : answer;
    }

    public String moderatePost(String postText) throws SQLException {
        if (!config.aiConfigured()) {
            return "Проверка постов через ИИ отключена. Локальная база работает, но анализ постов сейчас недоступен.";
        }

        List<KnowledgeEntry> rules = knowledgeRepository.findByCategories(MODERATION_CATEGORIES);
        if (rules.isEmpty()) {
            return "Не могу проверить пост: в базе знаний пока нет правил или лора для сверки.";
        }

        String context = rules.stream()
                .limit(10)
                .map(entry -> "[" + entry.getCategory() + "] " + entry.getTitle() + ": " + entry.getContent())
                .collect(Collectors.joining("\n\n"));

        return kieAiClient.chat(
                """
                Ты модератор проекта Аксис.
                Анализируй только по выданным правилам и лору.
                Если явных нарушений нет, так и скажи.
                """,
                """
                Проверь пост игрока на возможные нарушения:

                Пост:
                %s

                Правила и лор:
                %s

                Ответь по-русски в формате:
                Вердикт: ...
                Возможные нарушения: ...
                Что поправить: ...
                """.formatted(postText, context)
        );
    }

    public String answerWithDirectAi(String question) throws SQLException {
        if (!config.aiConfigured()) {
            return "Прямой ИИ-режим сейчас недоступен: `kie.ai` не настроен.";
        }

        List<KnowledgeHit> hits = search(question, 8);
        String context;
        if (hits.isEmpty()) {
            List<KnowledgeEntry> allEntries = knowledgeRepository.findAll().stream()
                    .limit(12)
                    .toList();
            if (allEntries.isEmpty()) {
                return "Прямой ИИ-режим включён, но база знаний пока пуста. Сначала добавьте правила, лор или описание Аксиса.";
            }
            context = allEntries.stream()
                    .map(entry -> """
                            Категория: %s
                            Заголовок: %s
                            Ключи: %s
                            Текст: %s
                            """.formatted(
                            entry.getCategory(),
                            entry.getTitle(),
                            entry.getKeywords(),
                            entry.getContent()
                    ))
                    .collect(Collectors.joining("\n"));
        } else {
            context = renderContext(hits);
        }

        String answer = kieAiClient.chat(
                """
                Ты Аксис, системный интеллект тоталитарного киберпанк-проекта во VK.
                Отвечай по-русски, в образе Аксиса, но не скатывайся в лишний пафос.
                Основывайся на контексте базы знаний ниже.
                Не используй markdown-разметку.
                Не используй символы `*`, `#`, списки с маркерами и другие декоративные элементы оформления.
                Пиши обычным текстом, короткими абзацами.
                Если вопрос просит идею или предложение, можешь делать осторожные выводы и предлагать варианты,
                но явно помечай их как предложение Аксиса, а не как уже установленный канон.
                Если контекста всё же не хватает, честно скажи, чего именно не хватает.
                """,
                """
                Вопрос администратора:
                %s

                Контекст проекта:
                %s

                Дай полезный ответ. Если уместно, раздели его на:
                - Что известно точно
                - Что можно предложить
                """.formatted(question, context)
        );

        if (answer.isBlank()) {
            return "Не удалось получить ответ от ИИ. Проверьте `KIE_API_KEY`, сеть и логи контейнера.";
        }
        return answer;
    }

    public List<KnowledgeHit> search(String query, int limit) throws SQLException {
        List<String> tokens = TextUtils.tokenize(query);
        return knowledgeRepository.findAll().stream()
                .map(entry -> new KnowledgeHit(entry, score(entry, tokens, query)))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingInt(KnowledgeHit::score).reversed())
                .limit(limit)
                .toList();
    }

    private int score(KnowledgeEntry entry, List<String> tokens, String fullQuery) {
        String category = lower(entry.getCategory());
        String title = lower(entry.getTitle());
        String keywords = lower(entry.getKeywords());
        String content = lower(entry.getContent());
        String query = lower(fullQuery);

        int score = 0;
        for (String token : tokens) {
            if (title.contains(token)) {
                score += 8;
            }
            if (keywords.contains(token)) {
                score += 5;
            }
            if (content.contains(token)) {
                score += 2;
            }
            if (category.contains(token)) {
                score += 3;
            }
        }

        if (!query.isBlank() && title.contains(query)) {
            score += 10;
        }
        if (!query.isBlank() && content.contains(query)) {
            score += 4;
        }
        return score;
    }

    private String renderDirectAnswer(List<KnowledgeHit> hits) {
        KnowledgeHit bestHit = hits.getFirst();
        if (hits.size() == 1 || bestHit.score() >= 20) {
            return """
                    По базе знаний:
                    [%s] %s

                    %s
                    """.formatted(
                    bestHit.entry().getCategory(),
                    bestHit.entry().getTitle(),
                    bestHit.entry().getContent()
            ).trim();
        }

        StringJoiner joiner = new StringJoiner("\n\n");
        joiner.add("Нашёл несколько подходящих записей:");
        hits.forEach(hit -> joiner.add("""
                [%s] %s
                %s
                """.formatted(
                hit.entry().getCategory(),
                hit.entry().getTitle(),
                TextUtils.truncate(hit.entry().getContent(), 260)
        ).trim()));
        return joiner.toString();
    }

    private String renderContext(List<KnowledgeHit> hits) {
        return hits.stream()
                .map(hit -> """
                        ID=%d
                        Категория: %s
                        Заголовок: %s
                        Ключи: %s
                        Текст: %s
                        """.formatted(
                        hit.entry().getId(),
                        hit.entry().getCategory(),
                        hit.entry().getTitle(),
                        hit.entry().getKeywords(),
                        hit.entry().getContent()
                ))
                .collect(Collectors.joining("\n"));
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
