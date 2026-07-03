package ru.axis.bot.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import ru.axis.bot.config.AppConfig;
import ru.axis.bot.db.ActivityRepository;
import ru.axis.bot.db.AdminRepository;
import ru.axis.bot.model.ActiveUser;
import ru.axis.bot.model.VkUser;
import ru.axis.bot.vk.VkApiClient;

public final class AdminService {
    private final AppConfig config;
    private final AdminRepository adminRepository;
    private final ActivityRepository activityRepository;
    private final VkApiClient vkApiClient;

    public AdminService(
            AppConfig config,
            AdminRepository adminRepository,
            ActivityRepository activityRepository,
            VkApiClient vkApiClient
    ) {
        this.config = config;
        this.adminRepository = adminRepository;
        this.activityRepository = activityRepository;
        this.vkApiClient = vkApiClient;
    }

    public boolean isAdmin(long userId) throws SQLException {
        return config.isBootstrapAdmin(userId) || adminRepository.exists(userId);
    }

    public void markActive(long userId, long peerId) throws SQLException {
        activityRepository.touch(userId, peerId);
    }

    public String renderPanel() throws Exception {
        List<ActiveUser> recentUsers = activityRepository.findRecent(10);
        if (recentUsers.isEmpty()) {
            return """
                    Панель администратора:
                    Активных пользователей пока нет.

                    Когда игроки начнут писать, здесь появится список, и их можно будет добавить командой:
                    /admin add id123456
                    """.trim();
        }

        List<VkUser> vkUsers = vkApiClient.getUsers(recentUsers.stream()
                .map(ActiveUser::userId)
                .toList());
        Set<Long> allAdmins = allAdminIds();

        String activeList = recentUsers.stream()
                .map(user -> formatActiveUser(user, findVkUser(vkUsers, user.userId()), allAdmins.contains(user.userId())))
                .collect(Collectors.joining("\n"));

        return """
                Панель администратора:
                Активные пользователи:
                %s

                Команды:
                /admin add id123456
                /admin remove id123456
                /admin list
                """.formatted(activeList).trim();
    }

    public String addAdmin(long targetUserId, long actorUserId) throws Exception {
        if (!activityRepository.hasSeenUser(targetUserId)) {
            return "Нельзя добавить этого пользователя через /admin: он ещё не появлялся среди активных.";
        }
        if (isAdmin(targetUserId)) {
            return "Пользователь id" + targetUserId + " уже является администратором.";
        }
        adminRepository.addAdmin(targetUserId, actorUserId);
        return "Пользователь id" + targetUserId + " добавлен в администраторы.";
    }

    public String removeAdmin(long targetUserId) throws Exception {
        if (config.isBootstrapAdmin(targetUserId)) {
            return "Этого администратора нельзя удалить через /admin, потому что он задан в ADMIN_IDS.";
        }
        boolean removed = adminRepository.removeAdmin(targetUserId);
        return removed
                ? "Пользователь id" + targetUserId + " удалён из администраторов."
                : "Пользователь id" + targetUserId + " не найден в списке динамических администраторов.";
    }

    public String renderAdminList() throws Exception {
        Set<Long> allAdmins = allAdminIds();
        List<VkUser> vkUsers = vkApiClient.getUsers(new ArrayList<>(allAdmins));
        return allAdmins.stream()
                .sorted(Comparator.naturalOrder())
                .map(id -> {
                    VkUser user = findVkUser(vkUsers, id);
                    String suffix = config.isBootstrapAdmin(id) ? " (из ADMIN_IDS)" : "";
                    return "- id" + id + " " + user.displayName() + suffix;
                })
                .collect(Collectors.joining("\n", "Администраторы:\n", ""));
    }

    public List<VkUser> findActiveUsersByName(long peerId, String query, int limit) throws Exception {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        if (normalizedQuery.isBlank()) {
            return List.of();
        }

        List<ActiveUser> activeUsers = activityRepository.findRecentByPeer(peerId, 100);
        List<VkUser> vkUsers = vkApiClient.getUsers(activeUsers.stream()
                .map(ActiveUser::userId)
                .toList());

        return vkUsers.stream()
                .filter(user -> matchesUser(user, normalizedQuery))
                .limit(limit)
                .toList();
    }

    private Set<Long> allAdminIds() throws SQLException {
        Set<Long> ids = new HashSet<>(config.adminIds());
        ids.addAll(adminRepository.findAllIds());
        return ids;
    }

    private VkUser findVkUser(List<VkUser> users, long userId) {
        return users.stream()
                .filter(user -> user.id() == userId)
                .findFirst()
                .orElse(new VkUser(userId, "", ""));
    }

    private String formatActiveUser(ActiveUser user, VkUser vkUser, boolean admin) {
        String adminMark = admin ? " [admin]" : "";
        return "- id" + user.userId() + " " + vkUser.displayName()
                + " | сообщений: " + user.messageCount()
                + " | последнее: " + user.lastSeenAt()
                + adminMark;
    }

    private boolean matchesUser(VkUser user, String query) {
        String displayName = user.displayName().toLowerCase();
        String firstName = user.firstName() == null ? "" : user.firstName().toLowerCase();
        String lastName = user.lastName() == null ? "" : user.lastName().toLowerCase();
        return displayName.contains(query) || firstName.contains(query) || lastName.contains(query);
    }
}
