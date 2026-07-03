package ru.axis.bot.model;

public record VkUser(
        long id,
        String firstName,
        String lastName
) {
    public String displayName() {
        String value = (firstName + " " + lastName).trim();
        return value.isBlank() ? "id" + id : value;
    }
}
