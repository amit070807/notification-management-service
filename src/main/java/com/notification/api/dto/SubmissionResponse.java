package com.notification.api.dto;

/**
 * The two things a submission can return: accepted (202) or suppressed (200).
 *
 * <p>Sealed so the set stays closed. Jackson serialises the concrete record, so neither body's JSON
 * changes by joining this hierarchy — {@link SubmissionAcceptedResponse} is byte-for-byte what phase 1
 * returned.
 */
public sealed interface SubmissionResponse permits SubmissionAcceptedResponse, SubmissionSuppressed {}
