package com.cumania.war.news;

/** The kinds of news the menu's four filter buttons pick, in the order of the buttons. No Bukkit classes. */
public enum NewsFilter {
    WAR, ALLIANCE, ULTIMATUM, TRUCE;

    /** Whether a news of this type is shown when this filter is on. The Staff's posts belong to no filter: they show only with every filter off. */
    public boolean matches(News.Type type) {
        return switch (this) {
            case WAR -> type == News.Type.WAR || type == News.Type.PEACE;
            case ALLIANCE -> switch (type) {
                case ALLIANCE, ALLIANCE_JOIN, ALLIANCE_LEAVE, ALLIANCE_END, VASSAL, INDEPENDENCE -> true;
                default -> false;
            };
            case ULTIMATUM -> type == News.Type.ULTIMATUM;
            case TRUCE -> type == News.Type.TRUCE || type == News.Type.NAP;
        };
    }
}
