package com.worknote.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper {
    void insert(UserRow row);
    UserRow findById(@Param("id") String id);
    UserRow findByEmp(@Param("emp") String emp);
    List<UserRow> findAll();
    List<DirectoryUser> findActiveDirectory();                    // 공유 대상 선택용 — active emp+name
    void update(UserRow row);
    int updateCredential(CredentialRow row);
    int countUsers();
    void stampLastLogin(@Param("id") String id, @Param("at") String at);
    CredentialRow findCredential(@Param("userId") String userId);
    void insertCredential(CredentialRow row);
}
