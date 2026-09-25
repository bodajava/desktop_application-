package com.examhalls.dao;

import com.examhalls.model.Role;

import java.util.List;
import java.util.Optional;

public interface RoleDao {
    List<Role> findAll();

    Optional<Role> findByName(String roleName);
}
