package de.joinside.evmap_service.pricing.mobidata;

import de.joinside.evmap_service.pricing.mobidata.MobiDataPricingProperties.OperatorBasis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hand-kept table ships in {@code application.yaml}; a typo there would start a container that quietly shows
 * fewer prices, or a date that does not parse would stop it. Binds the real file (the test classpath has its own {@code application.yaml}).
 */
class ShippedVatBasisTableTests {

    private static MobiDataPricingProperties shipped() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yaml", new FileSystemResource("src/main/resources/application.yaml"));
        // Placeholders such as ${MOBIDATA_PRICING_ENABLED:true} resolve to their defaults, as without an environment.
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bindOrCreate("evmap.pricing.mobidata", MobiDataPricingProperties.class);
    }

    @Test
    @DisplayName("every shipped entry has an operator, a basis, a check date and a source, and none is listed twice")
    void shippedTableIsComplete() throws IOException {
        MobiDataPricingProperties properties = shipped();

        assertThat(properties.vatBasis()).hasSizeGreaterThanOrEqualTo(15).allSatisfy(entry -> {
            assertThat(entry.operator()).isNotBlank();
            assertThat(entry.basis()).isIn(OcpiTariffs.TableBasis.NET, OcpiTariffs.TableBasis.GROSS);
            assertThat(entry.checkedOn()).isAfterOrEqualTo(LocalDate.parse("2026-10-01"));
            assertThat(entry.source()).isNotBlank();
        });
        assertThat(properties.recheckAfter()).isEqualTo(Period.ofMonths(6));
        // Throws on a duplicate or an incomplete entry.
        VatBasisTable table = VatBasisTable.of(properties.vatBasis());
        assertThat(table.basisOf("Allego")).isEqualTo(OcpiTariffs.TableBasis.NET);
        assertThat(table.basisOf("Energie und Wasserversorgung Bonn/Rhein-Sieg GmbH (EnW Bonn/Rhein-Sieg)"))
                .isEqualTo(OcpiTariffs.TableBasis.GROSS);
        assertThat(table.basisOf("Braunschweiger Versorgungs-Aktiengesellschaft & Co. KG"))
                .isEqualTo(OcpiTariffs.TableBasis.GROSS);
    }

    @Test
    @DisplayName("an operator the research could not settle stays out of the table")
    void undecidedOperatorsStayOut() throws IOException {
        List<String> listed = shipped().vatBasis().stream().map(OperatorBasis::operator).toList();

        // E-Werk Mittelbaden rounds AC and DC differently (ADR 0022, phase 5r); ChargePoint is a platform.
        assertThat(listed).doesNotContain("E-Werk Mittelbaden", "ChargePoint", "Aral pulse");
    }
}
