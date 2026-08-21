package dev.sylvain.planning.config;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;

/** Rate limit on the admin login form, per source address. */
@ConfigMapping(prefix = "planning.auth.connexion")
public interface ConfigConnexionAdmin {

    int maxEchecs();

    Duration dureeBlocage();
}
