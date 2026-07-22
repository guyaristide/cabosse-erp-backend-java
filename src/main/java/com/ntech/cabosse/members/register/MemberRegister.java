package com.ntech.cabosse.members.register;

import java.util.List;

/** Registre producteurs construit pour une campagne (backlog REG-01). */
public record MemberRegister(String title, List<RegisterRow> rows) {}
