package com.gilbom.auth.password.validation;

import com.gilbom.auth.password.PasswordPolicy;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordValidator
        implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(
            String value,
            ConstraintValidatorContext context
    ) {
        return PasswordPolicy.hasValidLength(value);
    }
}
