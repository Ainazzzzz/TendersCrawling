package crawling.service;

import crawling.dto.DetailInfo;
import crawling.dto.TenderDto;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class TenderScraperService {

    private static final String BASE_URL = "https://zakupki.okmot.kg/popp/view/order/list.xhtml";
    private static final String DETAIL_BASE = "https://zakupki.okmot.kg/popp/view/order/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int TIMEOUT_MS = 30_000;
    private static final int PAGE_DELAY_MS = 1_500;

    @Value("${tenderbot.max-pages:5}")
    private int maxPages;

    /**
     * Fetches the tender detail page and extracts city and experience requirement text.
     * City is parsed from the "Address and place of delivery" field.
     * experienceText is the actual qualification requirement, or null if none found.
     */
    public DetailInfo fetchDetailInfo(String detailUrl) {
        try {
            Document doc = Jsoup.connect(detailUrl)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .sslSocketFactory(trustAllSslFactory())
                    .get();
            return new DetailInfo(extractCity(doc), extractExperienceText(doc), extractGuaranteeRequired(doc));
        } catch (Exception e) {
            log.warn("Could not fetch detail page {}: {}", detailUrl, e.getMessage());
            return new DetailInfo(null, null, false);
        }
    }

    private String extractCity(Document doc) {
        // HTML: <span class="label">Address and place of delivery</span>
        //       <span class="bold">г. Бишкек ул. Чкалова, 3</span>
        for (Element label : doc.select("span.label")) {
            if (label.text().contains("Address and place of delivery")) {
                Element bold = label.nextElementSibling();
                if (bold != null) {
                    String addr = bold.text().trim();
                    Matcher m = Pattern.compile("г\\.\\s*([А-Яа-яёЁ][А-Яа-яёЁ\\-]{2,30})").matcher(addr);
                    if (m.find()) return m.group(1).trim();
                    return addr.split(",")[0].trim(); // fallback: first part before comma
                }
            }
        }
        return null;
    }

    private String extractExperienceText(Document doc) {
        // Step 1: reliable detection via full-text search.
        // If ANY of these phrases appear on the page → experience IS required.
        String fullText = doc.text().toLowerCase();
        boolean detected = fullText.contains("иметь опыт")
                || fullText.contains("наличии опыта")
                || fullText.contains("наличие опыта")
                || fullText.contains("аналогичных договоров")
                || fullText.contains("схожих договоров")
                || fullText.contains("аналогичных работ")
                || fullText.contains("аналогичных поставок")
                || fullText.contains("аналогичных услуг")
                || fullText.contains("опыт выполнения");

        if (!detected) return null;

        // Step 2: best-effort extraction of the specific requirement text for the notification.
        // Qualification table has 3 cols: №, label (КВАЛИФИКАЦИЯ), requirement (ТРЕБОВАНИЕ).
        for (Element td : doc.select("td")) {
            if (td.text().contains("Сведения о наличии опыта")) {
                Element next = td.nextElementSibling();
                if (next != null && !next.text().isBlank()) return next.text().trim();
            }
        }
        for (Element td : doc.select("td")) {
            String lower = td.text().toLowerCase();
            if ((lower.contains("иметь опыт") || lower.contains("аналогичных договоров")
                    || lower.contains("схожих договоров")) && td.text().length() > 15) {
                return td.text().trim();
            }
        }
        return "Требуется опыт выполнения аналогичных работ";
    }

    private boolean extractGuaranteeRequired(Document doc) {
        // The portal shows guarantee fee as "Размер обеспечения заявки" or "Гарантийный взнос"
        // with a numeric value. Zero or missing means no guarantee required.
        for (Element label : doc.select("span.label, td")) {
            String text = label.text().toLowerCase();
            if (text.contains("обеспечени") || text.contains("гарантийный взнос")) {
                Element sibling = label.nextElementSibling();
                if (sibling != null) {
                    String val = sibling.text().replaceAll("[^\\d.]", "").trim();
                    if (!val.isEmpty()) {
                        try {
                            return new BigDecimal(val).compareTo(BigDecimal.ZERO) > 0;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        // Fallback: search full text for non-zero guarantee amounts
        String fullText = doc.text();
        Matcher m = Pattern.compile("(?:обеспечени[ея]|гарантийный взнос)[^\\d]{0,60}([\\d\\s,.]+)\\s*(?:сом|kgs|KGS|KGS|руб)?",
                Pattern.CASE_INSENSITIVE).matcher(fullText);
        if (m.find()) {
            String val = m.group(1).replaceAll("[^\\d.]", "").trim();
            if (!val.isEmpty()) {
                try {
                    return new BigDecimal(val).compareTo(BigDecimal.ZERO) > 0;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    public List<TenderDto> fetchLatest() {
        // Step 1: GET first page — also captures session cookies and ViewState
        Connection.Response firstResponse;
        try {
            firstResponse = Jsoup.connect(BASE_URL)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .ignoreHttpErrors(true)
                    .sslSocketFactory(trustAllSslFactory())
                    .execute();
        } catch (Exception e) {
            log.error("Failed to fetch first page: {}", e.getMessage());
            return Collections.emptyList();
        }

        Map<String, String> cookies = firstResponse.cookies();
        Document firstPage;
        try {
            firstPage = firstResponse.parse();
        } catch (IOException e) {
            log.error("Failed to parse first page: {}", e.getMessage());
            return Collections.emptyList();
        }

        String viewState = extractViewState(firstPage);
        String tableId = detectTableId(firstPage);

        if (tableId == null) {
            log.error("Could not detect table component ID from page — site structure may have changed");
            return Collections.emptyList();
        }

        String formId = tableId.split(":")[0];
        log.debug("Detected tableId={}, formId={}", tableId, formId);

        List<TenderDto> result = new ArrayList<>(parseRows(firstPage));
        log.info("Page 1: found {} tenders", result.size());

        // Step 2: Fetch subsequent pages via JSF AJAX
        for (int page = 1; page < maxPages; page++) {
            try {
                Thread.sleep(PAGE_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            List<TenderDto> pageRows = fetchPageAjax(page, cookies, viewState, tableId, formId);
            if (pageRows.isEmpty()) {
                log.debug("Page {} returned no rows, stopping", page + 1);
                break;
            }
            log.info("Page {}: found {} tenders", page + 1, pageRows.size());
            result.addAll(pageRows);
        }

        return result;
    }

    private List<TenderDto> fetchPageAjax(int pageIndex, Map<String, String> cookies,
                                           String viewState, String tableId, String formId) {
        int firstRow = pageIndex * 10;
        try {
            Connection.Response response = Jsoup.connect(BASE_URL)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .cookies(cookies)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .sslSocketFactory(trustAllSslFactory())
                    // JSF partial-request headers
                    .header("Faces-Request", "partial/ajax")
                    .header("X-Requested-With", "XMLHttpRequest")
                    // PrimeFaces DataTable pagination params
                    .data("javax.faces.partial.ajax", "true")
                    .data("javax.faces.source", tableId)
                    .data("javax.faces.partial.execute", tableId)
                    .data("javax.faces.partial.render", tableId)
                    .data(tableId + "_pagination", "true")
                    .data(tableId + "_first", String.valueOf(firstRow))
                    .data(tableId + "_rows", "10")
                    .data(tableId + "_encodeFeature", "true")
                    .data(formId, formId)
                    .data("javax.faces.ViewState", viewState)
                    .method(Connection.Method.POST)
                    .execute();

            String xmlBody = response.body();
            String tableHtml = extractPartialHtml(xmlBody, tableId);
            if (tableHtml == null || tableHtml.isBlank()) {
                log.debug("Empty partial response for page {}", pageIndex + 1);
                return Collections.emptyList();
            }

            // Bare <tr> elements need a <table> wrapper or Jsoup discards them
            Document fragment = Jsoup.parse("<table><tbody>" + tableHtml + "</tbody></table>", BASE_URL);
            return parseRows(fragment);

        } catch (Exception e) {
            log.error("Failed to fetch page {}: {}", pageIndex + 1, e.getMessage());
            return Collections.emptyList();
        }
    }

    // JSF partial response: <partial-response><changes><update id="tableId"><![CDATA[<tr>...</tr>]]></update>
    // update.html() re-wraps in CDATA markers, which the HTML parser treats as a comment — extract raw.
    private String extractPartialHtml(String xmlBody, String tableId) {
        String marker = "id=\"" + tableId + "\"";
        int updatePos = xmlBody.indexOf(marker);
        if (updatePos < 0) return null;
        int cdataStart = xmlBody.indexOf("<![CDATA[", updatePos);
        int cdataEnd   = xmlBody.indexOf("]]>", cdataStart);
        if (cdataStart < 0 || cdataEnd < 0) return null;
        return xmlBody.substring(cdataStart + "<![CDATA[".length(), cdataEnd);
    }

    private List<TenderDto> parseRows(Document doc) {
        // tr[data-rk] is unique to tender rows — data-rk holds the numeric external ID
        Elements rows = doc.select("tr[data-rk]");
        List<TenderDto> tenders = new ArrayList<>();

        for (Element row : rows) {
            try {
                tenders.add(parseRow(row));
            } catch (Exception e) {
                log.warn("Skipping malformed row data-rk={}: {}", row.attr("data-rk"), e.getMessage());
            }
        }
        return tenders;
    }

    private TenderDto parseRow(Element row) {
        Elements tds = row.select("td");
        if (tds.size() < 9) throw new IllegalStateException("Expected ≥9 columns, got " + tds.size());

        String externalId = row.attr("data-rk");
        // ownText() returns text directly in the td, excluding child element text (the <span> labels)
        String tenderNumber = tds.get(0).ownText().trim();
        String company     = tds.get(1).ownText().trim();
        String procType    = tds.get(2).ownText().trim();
        String title       = tds.get(3).select("span.nameTender").text().trim();
        String href        = tds.get(4).select("a[href]").attr("href");
        String detailUrl   = DETAIL_BASE + href;
        String method      = tds.get(5).ownText().trim();
        BigDecimal amount  = parseAmount(tds.get(6).ownText().trim());
        LocalDateTime pub  = parseDate(tds.get(7).ownText().trim());
        LocalDateTime dead = parseDate(tds.get(8).ownText().trim());

        return TenderDto.builder()
                .externalId(externalId)
                .tenderNumber(tenderNumber)
                .company(company)
                .procurementType(procType)
                .title(title)
                .detailUrl(detailUrl)
                .procurementMethod(method)
                .plannedAmount(amount)
                .publishedAt(pub)
                .deadline(dead)
                .build();
    }

    private String detectTableId(Document doc) {
        // tbody.ui-datatable-data has id like "j_idt82:j_idt83:table_data"
        Element tbody = doc.select("tbody.ui-datatable-data").first();
        if (tbody == null) return null;
        String tbodyId = tbody.id(); // "j_idt82:j_idt83:table_data"
        return tbodyId.endsWith("_data") ? tbodyId.substring(0, tbodyId.length() - "_data".length()) : null;
    }

    private String extractViewState(Document doc) {
        Element el = doc.select("input[name=javax.faces.ViewState]").first();
        return el != null ? el.val() : "";
    }

    private BigDecimal parseAmount(String text) {
        if (text == null || text.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(text.replace(",", "").replace(" ", "").trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private LocalDateTime parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDateTime.parse(text.trim(), DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    // Self-signed/invalid certificate on zakupki.okmot.kg — trust all for this internal tool
    private static SSLSocketFactory trustAllSslFactory() {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new java.security.SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create trust-all SSL factory", e);
        }
    }
}
