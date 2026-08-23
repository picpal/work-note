package com.worknote.template;

/** note_template 한 행. ownerId == null 이면 시스템 템플릿. */
public record NoteTemplateRow(
    String id,
    String ownerId,
    String name,
    String body,
    String createdAt,
    String updatedAt
) {}
