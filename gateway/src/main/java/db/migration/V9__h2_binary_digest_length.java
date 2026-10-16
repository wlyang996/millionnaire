package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * MySQL LENGTH(binary) measures bytes; H2 2.x LENGTH first decodes UTF-8.
 * Keep deployed SQL migrations immutable and repair only the H2 constraint
 * through this versioned migration. MySQL 5.7/8 need no schema change.
 */
public class V9__h2_binary_digest_length extends BaseJavaMigration {
    @Override public void migrate(Context context)throws Exception{
        if(!context.getConnection().getMetaData().getDatabaseProductName().equals("H2"))return;
        try(var statement=context.getConnection().createStatement()){
            statement.execute("ALTER TABLE game_record DROP CONSTRAINT ck_game_record_values");
            statement.execute("ALTER TABLE game_record ADD CONSTRAINT ck_game_record_values CHECK (game_no >= 1 AND player_count >= 1 AND player_count <= 8 AND initial_cash >= 0 AND ended_at >= started_at AND OCTET_LENGTH(draft_sha256) = 32)");
        }
    }
}
