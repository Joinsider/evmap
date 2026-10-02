package de.joinside.evmap_service.pricing.mobidata;

import de.joinside.evmap_service.pricing.mobidata.MobiDataPricingProperties.OperatorBasis;
import de.joinside.evmap_service.pricing.mobidata.OcpiTariffs.TableBasis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VatBasisTableTests {
    private static OperatorBasis entry(String operator, TableBasis basis, String checkedOn) {
        return new OperatorBasis(operator, basis, checkedOn == null ? null : LocalDate.parse(checkedOn), "https://example.org");
    }

    @Test
    @DisplayName("matches operators case- and whitespace-insensitively; unlisted ones are unchecked")
    void lookup() {
        VatBasisTable table = VatBasisTable.of(List.of(entry("Allego", TableBasis.NET, "2026-10-01"),
                entry("TankE GmbH", TableBasis.GROSS, "2026-10-02")));

        assertThat(table.basisOf(" allego ")).isEqualTo(TableBasis.NET);
        assertThat(table.basisOf("TANKE GMBH")).isEqualTo(TableBasis.GROSS);
        assertThat(table.basisOf("Aral pulse")).isEqualTo(TableBasis.UNCHECKED);
        assertThat(table.basisOf(null)).isEqualTo(TableBasis.UNCHECKED);
        assertThat(VatBasisTable.of(null).basisOf("Allego")).isEqualTo(TableBasis.UNCHECKED);
    }

    @Test
    @DisplayName("a suspended entry reads as unchecked, and suspending reports only the first time")
    void suspension() {
        VatBasisTable table = VatBasisTable.of(List.of(entry("Mainova AG", TableBasis.NET, "2026-10-02")));

        assertThat(table.suspend("mainova ag")).isTrue();
        assertThat(table.suspend("Mainova AG")).isFalse();
        assertThat(table.suspend("Aral pulse")).isFalse();
        assertThat(table.basisOf("Mainova AG")).isEqualTo(TableBasis.UNCHECKED);
    }

    @Test
    @DisplayName("names the entries older than the re-check period, oldest first")
    void dueForRecheck() {
        VatBasisTable table = VatBasisTable.of(List.of(entry("A", TableBasis.NET, "2026-10-01"),
                entry("B", TableBasis.GROSS, "2026-03-01"), entry("C", TableBasis.GROSS, "2026-01-15")));

        assertThat(table.dueForRecheck(LocalDate.parse("2026-10-02"), Period.ofMonths(6)))
                .extracting(OperatorBasis::operator).containsExactly("C", "B");
    }

    @Test
    @DisplayName("refuses entries that say nothing or two things about an operator")
    void validation() {
        assertThatThrownBy(() -> VatBasisTable.of(List.of(entry(" ", TableBasis.NET, "2026-10-01"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VatBasisTable.of(List.of(entry("A", TableBasis.UNCHECKED, "2026-10-01"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VatBasisTable.of(List.of(entry("A", TableBasis.NET, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VatBasisTable.of(List.of(entry("A", TableBasis.NET, "2026-10-01"),
                entry("a", TableBasis.GROSS, "2026-10-01")))).isInstanceOf(IllegalArgumentException.class);
    }
}
