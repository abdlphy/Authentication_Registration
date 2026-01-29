package com.microservice.loginsystem.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.microservice.loginsystem.entity.UserRole;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

}
