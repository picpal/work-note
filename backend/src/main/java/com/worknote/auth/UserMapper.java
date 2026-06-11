package com.worknote.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
    void insert(UserRow row);
    UserRow findById(@Param("id") String id);
    UserRow findByEmp(@Param("emp") String emp);
    int countUsers();
    void stampLastLogin(@Param("id") String id, @Param("at") String at);
    CredentialRow findCredential(@Param("userId") String userId);
    void insertCredential(CredentialRow row);
}
