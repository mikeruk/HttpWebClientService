package reactive.httpwebclientservice.DTOs.db;


import jakarta.validation.constraints.*;
import reactive.httpwebclientservice.DTOs.enums.EyeColor;
import reactive.httpwebclientservice.DTOs.enums.HairColor;
import reactive.httpwebclientservice.DTOs.enums.MaritalStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;


public class ProfileDataDbDTO {


    private Long id;

    @PastOrPresent(message = "Last login date cannot be in the future")
    private LocalDateTime lastLogin;

    @NotNull(message = "Birth date is required")
    @Past(message = "Birth date must be in the past")
    private LocalDate birthDate;

    @NotBlank(message = "Country is required")
    private String country;

    @Size(min = 3, max = 20, message = "region must be between 3 and 20 characters")
    private String region;

    @Size(min = 3, max = 20, message = "city must be between 3 and 20 characters")
    private String city;

    @NotNull(message = "Marital status is required")
    private MaritalStatus maritalStatus;

    @NotNull(message = "Height is required")
    @DecimalMin(value = "1.5", message = "Height must be at least 1.5 meters")
    private BigDecimal height;

    @DecimalMin(value = "40", message = "Weight must be at least 40 kg")
    private BigDecimal weight;

    @NotNull(message = "Hair color is required")
    private HairColor hairColor;

    @NotNull(message = "Eye color is required")
    private EyeColor eyeColor;


    @Max(value = 10, message = "Children count cannot be greater than 10")
    @NotNull(message = "Children is required")
    private Integer children;

    @Size(min = 3, max = 20, message = "religion must be between 3 and 20 characters")
    private String religion;

    private Boolean smoking;

    private Boolean drinking;

    @Size(min = 3, max = 20, message = "education must be between 3 and 20 characters")
    private String education;

    @Size(min = 3, max = 20, message = "occupation must be between 3 and 20 characters")
    private String occupation;

    @NotBlank(message = "Languages field cannot be empty")
    private String languages;




    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(LocalDateTime lastLogin) {
        this.lastLogin = lastLogin;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public MaritalStatus getMaritalStatus() {
        return maritalStatus;
    }

    public void setMaritalStatus(MaritalStatus maritalStatus) {
        this.maritalStatus = maritalStatus;
    }

    public BigDecimal getHeight() {
        return height;
    }

    public void setHeight(BigDecimal height) {
        this.height = height;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public HairColor getHairColor() {
        return hairColor;
    }

    public void setHairColor(HairColor hairColor) {
        this.hairColor = hairColor;
    }

    public EyeColor getEyeColor() {
        return eyeColor;
    }

    public void setEyeColor(EyeColor eyeColor) {
        this.eyeColor = eyeColor;
    }

    public Integer getChildren() {
        return children;
    }

    public void setChildren(Integer children) {
        this.children = children;
    }

    public String getReligion() {
        return religion;
    }

    public void setReligion(String religion) {
        this.religion = religion;
    }

    public Boolean getSmoking() {
        return smoking;
    }

    public void setSmoking(Boolean smoking) {
        this.smoking = smoking;
    }

    public Boolean getDrinking() {
        return drinking;
    }

    public void setDrinking(Boolean drinking) {
        this.drinking = drinking;
    }

    public String getEducation() {
        return education;
    }

    public void setEducation(String education) {
        this.education = education;
    }

    public @Size(min = 3, max = 20, message = "occupation must be between 3 and 20 characters") String getOccupation() {
        return occupation;
    }

    public void setOccupation(String occupation) {
        this.occupation = occupation;
    }

    public String getLanguages() {
        return languages;
    }

    public void setLanguages(String languages) {
        this.languages = languages;
    }

    @Override
    public String toString() {
        return "ProfileDataDbDTO{" +
                "id=" + id +
                ", lastLogin=" + lastLogin +
                ", birthDate=" + birthDate +
                ", country='" + country + '\'' +
                ", region='" + region + '\'' +
                ", city='" + city + '\'' +
                ", maritalStatus=" + maritalStatus +
                ", height=" + height +
                ", weight=" + weight +
                ", hairColor=" + hairColor +
                ", eyeColor=" + eyeColor +
                ", children=" + children +
                ", religion='" + religion + '\'' +
                ", smoking=" + smoking +
                ", drinking=" + drinking +
                ", education='" + education + '\'' +
                ", occupation='" + occupation + '\'' +
                ", languages='" + languages + '\'' +
                '}' + '\n';
    }
}
