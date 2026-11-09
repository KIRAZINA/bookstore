package com.example.bookstore.service;

import com.example.bookstore.base.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BookCatalogCacheTest extends AbstractIntegrationTest {

    @Autowired
    private CacheManager cacheManager;

    @Test
    void bookCaches_exist() {
        assertNotNull(cacheManager.getCache("books"));
        assertNotNull(cacheManager.getCache("bookById"));
    }

    @Test
    void searchCache_isNotDisabledWhileP21Partial() {
        assertNotNull(cacheManager.getCache("books"));
    }
}