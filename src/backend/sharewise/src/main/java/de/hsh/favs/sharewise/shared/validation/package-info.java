/**
 * Transport-independent input conversion and structured validation failures.
 *
 * <p>The types in this named interface do not depend on HTTP or Spring MVC. Inbound
 * adapters may map the failures to their own protocol responses.
 */
@NamedInterface("validation")
package de.hsh.favs.sharewise.shared.validation;

import org.springframework.modulith.NamedInterface;
