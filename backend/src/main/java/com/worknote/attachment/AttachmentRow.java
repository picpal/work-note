package com.worknote.attachment;

/** attachment 행. 바이너리는 worknote.upload.dir/relPath, 메타는 DB. */
public record AttachmentRow(String id, String nodeId, String filename, String ext,
                            String mime, long size, String relPath, String createdBy,
                            String createdAt) {}
