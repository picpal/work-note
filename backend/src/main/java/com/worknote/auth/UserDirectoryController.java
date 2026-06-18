package com.worknote.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 사용자 디렉토리(비관리자) — 공유 대상(@) 선택용. 인증된 사용자면 누구나, active의 emp+name만.
    server 모드 미인증은 AuthFilter가 먼저 401. local 모드는 무인증(사용자 개념 없음). */
@RestController
@RequestMapping("/api/users")
public class UserDirectoryController {

    private final UserMapper users;

    public UserDirectoryController(UserMapper users) {
        this.users = users;
    }

    @GetMapping("/directory")
    public List<DirectoryUser> directory() {
        return users.findActiveDirectory();
    }
}
