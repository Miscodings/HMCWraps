package de.skyslycer.hmcwraps.serialization.database;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public class DatabaseSettings {

    private String type = "pdc";
    private MySQLSettings mysql = new MySQLSettings();

    public String getType() {
        return type == null ? "pdc" : type;
    }

    public MySQLSettings getMysql() {
        return mysql;
    }

    @ConfigSerializable
    public static class MySQLSettings {

        private String database = "database";
        private String password = "cherryBomb";
        private int port = 3306;
        private String host = "localhost";
        private String user = "username";

        public String getDatabase() {
            return database;
        }

        public String getPassword() {
            return password;
        }

        public int getPort() {
            return port;
        }

        public String getHost() {
            return host;
        }

        public String getUser() {
            return user;
        }

    }

}
