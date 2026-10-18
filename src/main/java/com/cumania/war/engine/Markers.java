package com.cumania.war.engine;

import com.cumania.war.site.SiteData;

/** The map the site and the mod read (dynmap's markers): who holds each city is its icon's colour. */
public interface Markers {
    int NONE = -1, CONTESTED = -2, UNCONTROLLED = -3;

    /** The side whose colour the city's icon has, CONTESTED, UNCONTROLLED, or NONE when the icon has no war colour (the city's owner holds it). */
    int read(SiteData.War war, SiteData.City city);

    /** Paints the icon: a side's colour (0 = red, 1 = blue, 2 = green), CONTESTED, UNCONTROLLED, or NONE to take the war colour off. False when it could not. */
    boolean write(SiteData.War war, SiteData.City city, int state);
}
