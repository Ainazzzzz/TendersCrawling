package crawling.dto;

/**
 * Data extracted from the tender's detail page.
 * experienceText is null when no experience requirement was found.
 */
public record DetailInfo(String city, String experienceText) {
    public boolean hasExperience() {
        return experienceText != null && !experienceText.isBlank();
    }
}
