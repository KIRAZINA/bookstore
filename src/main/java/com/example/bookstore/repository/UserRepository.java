package com.example.bookstore.repository;

import com.example.bookstore.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// @Repository не обязателен для Spring Data репозиториев — Spring создаст bean автоматически
@Repository
public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
}