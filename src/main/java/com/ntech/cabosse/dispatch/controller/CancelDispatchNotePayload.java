package com.ntech.cabosse.dispatch.controller;

import jakarta.validation.constraints.Size;

/** Motif d'annulation d'un bordereau de sortie (CE-195). */
public record CancelDispatchNotePayload(@Size(max = 500) String reason) {}
