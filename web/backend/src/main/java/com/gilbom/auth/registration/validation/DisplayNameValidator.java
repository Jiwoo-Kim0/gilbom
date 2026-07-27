package com.gilbom.auth.registration.validation;

import com.gilbom.auth.registration.DisplayNamePolicy;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DisplayNameValidator
        implements ConstraintValidator<ValidDisplayName, String> {

    @Override
    public boolean isValid(
            String value,
            ConstraintValidatorContext context
    ) {
        return DisplayNamePolicy.isValid(value);
    }
}
