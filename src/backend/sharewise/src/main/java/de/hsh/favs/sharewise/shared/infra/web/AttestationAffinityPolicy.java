package de.hsh.favs.sharewise.shared.infra.web;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded declarative request projection; never an authorization decision. */
public record AttestationAffinityPolicy(String policy, List<String> parameters) {
    public AttestationAffinityPolicy {
        Objects.requireNonNull(policy, "policy");
        parameters = List.copyOf(parameters);
        switch (policy) {
            case "never", "path_affine", "request_affine" -> {
                if (!parameters.isEmpty()) throw new IllegalArgumentException("Unexpected affinity parameters");
            }
            case "query_affine" -> {
                if (parameters.isEmpty() || parameters.size() > 8 || new HashSet<>(parameters).size() != parameters.size()
                        || parameters.stream().anyMatch(p -> p.isBlank() || p.length() > 64
                            || p.chars().anyMatch(Character::isISOControl))) {
                    throw new IllegalArgumentException("Invalid query affinity parameters");
                }
            }
            default -> throw new IllegalArgumentException("Unknown affinity policy");
        }
    }
    public static AttestationAffinityPolicy never() { return new AttestationAffinityPolicy("never", List.of()); }
    public static AttestationAffinityPolicy pathAffine() { return new AttestationAffinityPolicy("path_affine", List.of()); }
    public static AttestationAffinityPolicy requestAffine() { return new AttestationAffinityPolicy("request_affine", List.of()); }
    public static AttestationAffinityPolicy queryAffine(String... names) {
        return new AttestationAffinityPolicy("query_affine", List.of(names));
    }
}
