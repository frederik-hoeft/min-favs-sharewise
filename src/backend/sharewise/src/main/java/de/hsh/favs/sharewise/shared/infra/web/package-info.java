/**
 * Published HTTP API error contracts and boundary validation exceptions for inbound REST
 * controllers.
 *
 * <p>This package is the named interface {@code shared::web} of the {@code shared} module.
 * Modules whose controllers build {@code ProblemDetail} responses or throw boundary validation
 * exceptions declare {@code "shared :: web"} in their allowed dependencies. The global
 * {@code @RestControllerAdvice} implementation lives in the {@code internal} sub-package, which is
 * not published.
 */
@NamedInterface("web")
package de.hsh.favs.sharewise.shared.infra.web;

import org.springframework.modulith.NamedInterface;
