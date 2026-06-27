package crawling.dto;

/**
 * Data extracted from the tender's detail page.
 * experienceText is null when no experience requirement was found.
 * guaranteeRequired is true when a non-zero guarantee fee is detected.
 */
public record DetailInfo(String city, String experienceText, boolean guaranteeRequired) {
    public boolean hasExperience() {
        return experienceText != null && !experienceText.isBlank();
    }
}
