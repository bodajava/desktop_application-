package com.examhalls.dao;

import java.util.List;
import java.util.Optional;

/**
 * Common CRUD contract. All methods throw {@link com.examhalls.exception.AppException}
 * (unchecked) with a translated Oracle error; they never leak SQLException.
 *
 * @param <T> entity record type
 */
public interface CrudDao<T> {

    Optional<T> findById(long id);

    List<T> findAll();

    /** @return the generated primary key */
    long insert(T entity);

    /** @throws com.examhalls.exception.AppException NOT_FOUND if the row no longer exists / is archived */
    void update(T entity);

    /**
     * Deletes by id. For USERS / TEACHERS / ROOMS this is a soft delete through the V_* view;
     * for the other tables it is a physical delete (FK errors are translated).
     */
    void delete(long id);
}
