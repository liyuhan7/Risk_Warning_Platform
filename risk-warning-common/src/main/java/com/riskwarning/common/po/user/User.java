package com.riskwarning.common.po.user;

import lombok.Data;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "t_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String password;

    private String fullName;

    private String avatarUrl;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
