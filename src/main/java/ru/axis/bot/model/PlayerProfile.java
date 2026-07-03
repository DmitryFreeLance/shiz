package ru.axis.bot.model;

public final class PlayerProfile {
    private long vkUserId;
    private String vkProfileUrl;
    private String characterName;
    private String characterGender;
    private String characterAge;
    private String spectrum;
    private String characterIndex;
    private String note;

    public long getVkUserId() {
        return vkUserId;
    }

    public void setVkUserId(long vkUserId) {
        this.vkUserId = vkUserId;
    }

    public String getVkProfileUrl() {
        return vkProfileUrl;
    }

    public void setVkProfileUrl(String vkProfileUrl) {
        this.vkProfileUrl = vkProfileUrl;
    }

    public String getCharacterName() {
        return characterName;
    }

    public void setCharacterName(String characterName) {
        this.characterName = characterName;
    }

    public String getCharacterGender() {
        return characterGender;
    }

    public void setCharacterGender(String characterGender) {
        this.characterGender = characterGender;
    }

    public String getCharacterAge() {
        return characterAge;
    }

    public void setCharacterAge(String characterAge) {
        this.characterAge = characterAge;
    }

    public String getSpectrum() {
        return spectrum;
    }

    public void setSpectrum(String spectrum) {
        this.spectrum = spectrum;
    }

    public String getCharacterIndex() {
        return characterIndex;
    }

    public void setCharacterIndex(String characterIndex) {
        this.characterIndex = characterIndex;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
