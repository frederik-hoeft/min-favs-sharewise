package de.hsh.favs.sharewise.shared.infra.web;

/** Supplementary credential wire contract shared by HTTP adapters. */
public final class AttestationTransport {
    public static final String HEADER = "ShareWise-Attestation";
    public static final int MAX_COUNT = 4;
    public static final int MAX_HEADER_LENGTH = 8192;

    private AttestationTransport() { }
}
