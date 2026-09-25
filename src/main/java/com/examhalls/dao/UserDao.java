package com.examhalls.dao;

import com.examhalls.model.User;

import java.util.Optional;

/** Reads never return the password hash, except {@link #findByUsernameWithHash}. */
public interface UserDao extends CrudDao<User> {

    /** For authentication only. Case-insensitive username match; active users only. */
    Optional<User> findByUsernameWithHash(String username);

    void updatePasswordHash(long userId, String newHash);
}
