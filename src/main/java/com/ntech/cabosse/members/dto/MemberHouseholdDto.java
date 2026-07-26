package com.ntech.cabosse.members.dto;

import com.ntech.cabosse.members.entity.MemberHousehold;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ménage du producteur (backlog MEM-08). Tous les champs sont facultatifs :
 * une enquête peut être partielle. La cohérence entre les valeurs
 * renseignées est vérifiée côté service.
 */
@Schema(description = "Composition du ménage du producteur")
public record MemberHouseholdDto(
        @Min(0) @Max(50) Integer spousesCount,
        @Min(0) @Max(100) Integer childrenCount,
        @Min(0) @Max(100) Integer girlsCount,
        @Min(0) @Max(100) Integer boysCount,
        @Min(0) @Max(100) Integer children0to4,
        @Min(0) @Max(100) Integer children5to17,
        @Min(0) @Max(100) Integer childrenOver17,
        @Min(0) @Max(100) Integer childrenSchooled,
        @Min(0) @Max(100) Integer childrenNotSchooled,
        @Size(max = 200) String childrenActivity
) {
    public static MemberHouseholdDto from(MemberHousehold e) {
        if (e == null) {
            return new MemberHouseholdDto(null, null, null, null, null,
                    null, null, null, null, null);
        }
        return new MemberHouseholdDto(
                e.spousesCount, e.childrenCount, e.girlsCount, e.boysCount,
                e.children0to4, e.children5to17, e.childrenOver17,
                e.childrenSchooled, e.childrenNotSchooled, e.childrenActivity);
    }

    public MemberHousehold toEntity() {
        MemberHousehold h = new MemberHousehold();
        h.spousesCount = spousesCount;
        h.childrenCount = childrenCount;
        h.girlsCount = girlsCount;
        h.boysCount = boysCount;
        h.children0to4 = children0to4;
        h.children5to17 = children5to17;
        h.childrenOver17 = childrenOver17;
        h.childrenSchooled = childrenSchooled;
        h.childrenNotSchooled = childrenNotSchooled;
        h.childrenActivity = childrenActivity == null || childrenActivity.isBlank()
                ? null : childrenActivity.trim();
        return h;
    }
}
