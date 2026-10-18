package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Sha256Test {

    @Test
    void hashesTheUtf8BytesInLowerCaseHex() {
        assertThat(Sha256.hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Sha256.hex("é")).isNotEqualTo(Sha256.hex("e")).hasSize(64);
    }
}
