package de.hsh.favs.sharewise.shared.infra.web;

/** Standard domain-principal issuance request. The authority is selected by the local API route. */
public record AttestationIssueRequest(String kind, String scope) { }
