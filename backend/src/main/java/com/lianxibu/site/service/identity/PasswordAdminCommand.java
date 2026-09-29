package com.lianxibu.site.service.identity;

import com.lianxibu.site.repository.identity.JdbcPasswordAdminStore;
import java.io.Console;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Interactive host CLI. No password or database secret is accepted as an argument. */
public final class PasswordAdminCommand {
    private PasswordAdminCommand() { }

    public record Action(String mode, String userId, String username) { }

    public static Action parse(String[] args) {
        if (args.length == 3 && "init".equals(args[0])) return new Action("init", args[1], args[2]);
        if (args.length == 2 && "reset".equals(args[0])) return new Action("reset", null, args[1]);
        throw new IllegalArgumentException("usage: init <user-id> <username> | reset <username>");
    }

    public static void main(String[] args) {
        Action action = parse(args);
        Console console = System.console();
        if (console == null) throw new IllegalStateException("interactive host console required");
        String url = System.getenv("LIANXIBU_DB_URL");
        String username = System.getenv("LIANXIBU_DB_USERNAME");
        if (url == null || !url.startsWith("jdbc:mysql://") || username == null || username.isBlank()) {
            throw new IllegalStateException("database host configuration required");
        }
        char[] databasePassword = console.readPassword("Database password: ");
        char[] password = console.readPassword("New account password: ");
        char[] confirmation = console.readPassword("Repeat new account password: ");
        try {
            if (databasePassword == null || password == null || confirmation == null
                    || !Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("password confirmation failed");
            }
            var dataSource = new DriverManagerDataSource(url, username, new String(databasePassword));
            var admin = new PasswordAdministration(new JdbcPasswordAdminStore(dataSource, Clock.systemUTC()),
                    new Argon2Passwords());
            if ("init".equals(action.mode())) admin.initialize(action.userId(), action.username(),
                    new String(password));
            else admin.reset(action.username(), new String(password));
            console.printf("Password action completed. Existing sessions were revoked on reset.%n");
        } finally {
            if (databasePassword != null) Arrays.fill(databasePassword, '\0');
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
}
