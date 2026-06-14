package com.worknote.pii;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface PiiMapper {
    PiiFlagRow findFlag(@Param("nodeId") String nodeId);
    void insertFlag(PiiFlagRow row);
    void updateFlag(PiiFlagRow row);
    void deleteFlag(@Param("nodeId") String nodeId);
    void deleteFlagsIn(@Param("ids") List<String> ids);
    List<PiiFlagRow> activeFlags();
    List<Map<String, Object>> adminList();
    List<Map<String, Object>> adminRequests();

    void insertNotice(PiiNoticeRow row);
    void deleteNoticesIn(@Param("ids") List<String> ids);
    Long findUnackedNoticeId(@Param("nodeId") String nodeId, @Param("recipient") String recipient, @Param("kind") String kind);
    void touchNotice(@Param("id") Long id, @Param("message") String message, @Param("sentAt") String sentAt);
    List<Map<String, Object>> noticesFor(@Param("recipient") String recipient);
    void ack(@Param("recipient") String recipient, @Param("ids") List<Long> ids);
}
