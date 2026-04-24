package com.example.bookstore.controller.dto;

import java.math.BigDecimal;

public class BookResponse {
    private Long id;
    private String title;
    private String author;
    private BigDecimal price;
    private String category;
    private Integer stock;

    public BookResponse() {}

    public BookResponse(Long id, String title, String author, BigDecimal price, String category, Integer stock) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.price = price;
        this.category = category;
        this.stock = stock;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
}
