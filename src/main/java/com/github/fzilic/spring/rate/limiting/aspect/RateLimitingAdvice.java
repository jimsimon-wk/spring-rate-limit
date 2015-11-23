/*
 * Copyright (c) 2015 Franjo Žilić <frenky666@gmail.com>
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package com.github.fzilic.spring.rate.limiting.aspect;

import com.github.fzilic.spring.rate.limiting.CallBlockedException;
import com.github.fzilic.spring.rate.limiting.RateLimitExceededException;
import com.github.fzilic.spring.rate.limiting.RateLimited;
import com.github.fzilic.spring.rate.limiting.checker.RateChecker;
import com.github.fzilic.spring.rate.limiting.key.KeyGenerator;
import com.github.fzilic.spring.rate.limiting.options.Options;
import com.github.fzilic.spring.rate.limiting.options.OptionsResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.fzilic.spring.rate.limiting.util.JoinPointUtil.findAnnotation;

@Aspect
public class RateLimitingAdvice {

  private static final Logger log = LoggerFactory.getLogger(RateLimitingAdvice.class);

  private final OptionsResolver configurationResolver;

  private final KeyGenerator keyGenerator;

  private final RateChecker rateChecker;

  public RateLimitingAdvice(final KeyGenerator keyGenerator, final OptionsResolver configurationResolver, final RateChecker rateChecker) {
    this.configurationResolver = configurationResolver;
    this.keyGenerator = keyGenerator;
    this.rateChecker = rateChecker;
  }

  @Around("@annotation(com.github.fzilic.spring.rate.limiting.RateLimited) || @within(com.github.fzilic.spring.rate.limiting.RateLimited)")
  public Object rateLimit(final ProceedingJoinPoint joinPoint) throws Throwable {
    final RateLimited rateLimited = findAnnotation(joinPoint, RateLimited.class);
    final String key = keyGenerator.key(rateLimited.key(), rateLimited.keyExpression(), joinPoint);
    final Options options = configurationResolver.resolve(key, rateLimited, joinPoint);

    if (options.blocked()) {
      throw new CallBlockedException("Execution is blocked by configuration");
    }

    // skip disabled limiters
    if (options.enabled()) {

      Boolean canExecute;
      Integer retryCount = options.retryEnabled() ? options.retry().retryCount() + 1 : 1;
      do {
        canExecute = rateChecker.check(key, options.maxRequests(), options.interval());

        if (!canExecute && options.retryEnabled()) {
          try {
            Thread.sleep(options.retry().retryInterval().unit().toMillis(options.retry().retryInterval().interval()));
          }
          catch (final InterruptedException exception) {
            log.error("Execution retry was interrupted", exception);
            throw new RateLimitExceededException("Interrupted while retrying", exception);

          }
        }
      } while (!canExecute && --retryCount > 0);


      if (!canExecute) {
        throw new RateLimitExceededException("Rate limit has been exceeded");
      }
    }

    return joinPoint.proceed();
  }

}
