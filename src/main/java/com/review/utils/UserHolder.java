package com.review.utils;

import com.review.dto.UserDTO;

public class UserHolder {

    private static final ThreadLocal<UserDTO> tl = new InheritableThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }

}
