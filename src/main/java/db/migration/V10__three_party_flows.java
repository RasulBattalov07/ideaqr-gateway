package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Трёхсторонние сценарии «Услуги и быт» и «Бизнес и магазины».
 *
 * <p>Диспетчерская модель бытовых услуг вводит роль {@code EXECUTOR} (универсальный
 * исполнитель — сантехник/электрик/уборка без разделения), розничный чек-аут — роль
 * {@code CASHIER} (кассир, сканирующий QR покупателя) и тип запроса {@code PURCHASE}
 * (линия чека: корзина → оплачен → выдан). Соответствующие CHECK-ограничения V1/V9
 * расширяются тем же приёмом, что и в {@link V9__context_aware_identity}: имена
 * констрейнтов ищутся через {@code information_schema}, потому что H2 и PostgreSQL
 * автоименуют их по-разному.</p>
 */
public class V10__three_party_flows extends BaseJavaMigration {

    private record CheckSpec(String table, String column, String constraintName, List<String> values) {}

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();

        List<CheckSpec> specs = List.of(
                new CheckSpec("identity_roles", "role", "ck_identity_roles_role", List.of(
                        "CITIZEN", "DOCTOR", "ENGINEER", "INSPECTOR", "RETAIL_ADMIN", "OBJECT_OWNER",
                        "ORG_STAFF", "ADMIN", "PHARMACIST", "SELLER", "SERVICE_OPERATOR", "POLICE",
                        "EXECUTOR", "CASHIER")),
                new CheckSpec("requests", "request_type", "ck_requests_request_type", List.of(
                        "ACCESS", "QR_CREATION", "REPORT_ISSUE", "SOS", "WORKING_MODE",
                        "OBJECT_LIFECYCLE", "SERVICE_ORDER", "PURCHASE")));

        for (CheckSpec spec : specs) {
            for (String name : findCheckConstraints(c, spec.table(), spec.column())) {
                try (Statement st = c.createStatement()) {
                    st.execute("alter table " + spec.table() + " drop constraint " + quote(name));
                }
            }
            String inList = String.join("','", spec.values());
            try (Statement st = c.createStatement()) {
                st.execute("alter table " + spec.table() + " add constraint " + spec.constraintName()
                        + " check (" + spec.column() + " in ('" + inList + "'))");
            }
        }
    }

    /**
     * Names of CHECK constraints on {@code table} whose clause references {@code column}.
     * Uses the ANSI {@code information_schema} views present in both H2 2.x and PostgreSQL;
     * identifier case is normalised because H2 stores upper-case and PostgreSQL lower-case names.
     */
    private List<String> findCheckConstraints(Connection c, String table, String column) throws Exception {
        List<String> names = new ArrayList<>();
        String sql = "select tc.constraint_name, cc.check_clause "
                + "from information_schema.table_constraints tc "
                + "join information_schema.check_constraints cc "
                + "  on tc.constraint_name = cc.constraint_name "
                + " and tc.constraint_schema = cc.constraint_schema "
                + "where tc.constraint_type = 'CHECK' and upper(tc.table_name) = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String clause = rs.getString("check_clause");
                    String upper = clause == null ? "" : clause.toUpperCase(Locale.ROOT);
                    // PostgreSQL surfaces NOT NULL as synthetic, non-droppable CHECK rows
                    // ("col IS NOT NULL") — those must never be touched.
                    if (upper.contains(column.toUpperCase(Locale.ROOT)) && !upper.contains("IS NOT NULL")) {
                        names.add(rs.getString("constraint_name"));
                    }
                }
            }
        }
        return names;
    }

    /** Quote the found identifier verbatim — H2 names are upper-case, PostgreSQL lower-case. */
    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
