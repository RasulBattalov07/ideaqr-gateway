package com.ideaqr.gateway.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Имитация интеграции с eGov (Phase 2 — «умная регистрация»). По номеру телефона
 * «находит» гражданина в государственной базе: ФИО, ИИН, дату рождения и адрес.
 *
 * <p>Детерминированно: последняя цифра нормализованного номера выбирает персону из
 * пула, а ИИН достраивается из даты рождения (настоящий казахстанский формат
 * ГГММДД + век/пол + порядковые цифры) и хэша номера — поэтому один и тот же номер
 * всегда возвращает одного и того же «гражданина», без внешних вызовов и без
 * хранения справочника. Демо-сценарий: номер, оканчивающийся на <b>7</b>
 * (например +7 777 777 77 77), — это флагманская персона <b>Расул Батталов</b>.</p>
 */
@Service
public class MockEgovService {

    /** Найденная в «eGov» персона. Всё, что нужно для регистрации и досье. */
    public record EgovPerson(String firstName, String lastName, String gender,
                             String birthDateIso, String birthDateDisplay,
                             String iin, String address, String city) {}

    private record Persona(String firstName, String lastName, String gender,
                           String birthIso, String address) {}

    /** Пул демо-персон; индекс = последняя цифра номера. Расул Батталов — индекс 7. */
    private static final List<Persona> PERSONAS = List.of(
            new Persona("Айгерим", "Досанова", "Женский", "1998-03-12", "ул. Кабанбай батыра 42, кв. 17"),
            new Persona("Тимур", "Жаксылыков", "Мужской", "1995-11-02", "пр. Мәңгілік Ел 55, кв. 8"),
            new Persona("Динара", "Ержанова", "Женский", "2001-07-25", "ул. Сығанақ 18, кв. 133"),
            new Persona("Алишер", "Касымов", "Мужской", "1992-01-30", "ш. Коргалжын 3, кв. 61"),
            new Persona("Жанна", "Абишева", "Женский", "1999-09-14", "ул. Тұран 37, кв. 5"),
            new Persona("Ерасыл", "Мукашев", "Мужской", "2003-05-08", "ул. Акмешит 11, кв. 74"),
            new Persona("Салтанат", "Бекова", "Женский", "1997-12-19", "пр. Кабанбай батыра 60/2, кв. 21"),
            new Persona("Расул", "Батталов", "Мужской", "2007-04-20", "пр. Тұран 55/3, кв. 214"),
            new Persona("Мадина", "Сериккызы", "Женский", "2000-02-11", "ул. Ұлы Дала 27, кв. 96"),
            new Persona("Данияр", "Оспан", "Мужской", "1994-08-06", "ул. Достык 12, кв. 40"));

    /**
     * Приводит номер к каноническому виду «7XXXXXXXXXX» (11 цифр, КЗ). Принимает
     * +7 / 8 / без префикса, любые разделители. Невалидный номер → IllegalArgumentException
     * с локализованным сообщением (уйдёт клиенту как 400).
     */
    public String normalizePhone(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() == 11 && digits.startsWith("8")) {
            digits = "7" + digits.substring(1);
        }
        if (digits.length() == 10) {
            digits = "7" + digits;
        }
        if (digits.length() != 11 || !digits.startsWith("7")) {
            throw new IllegalArgumentException("Укажите корректный казахстанский номер телефона (+7 XXX XXX XX XX).");
        }
        return digits;
    }

    /** Красивый вид для интерфейса: +7 777 123-45-67. */
    public String displayPhone(String normalized) {
        return "+7 " + normalized.substring(1, 4) + " " + normalized.substring(4, 7)
                + "-" + normalized.substring(7, 9) + "-" + normalized.substring(9, 11);
    }

    /** «Запрос в eGov»: детерминированная персона по номеру телефона. */
    public EgovPerson lookup(String normalizedPhone) {
        int lastDigit = normalizedPhone.charAt(normalizedPhone.length() - 1) - '0';
        Persona p = PERSONAS.get(lastDigit % PERSONAS.size());
        return new EgovPerson(p.firstName(), p.lastName(), p.gender(),
                p.birthIso(), displayDate(p.birthIso()),
                buildIin(p, normalizedPhone), p.address(), "Астана");
    }

    // ------------------------------------------------------------------

    private String displayDate(String iso) {
        String[] parts = iso.split("-");
        return parts[2] + "." + parts[1] + "." + parts[0];
    }

    /**
     * ИИН по реальной схеме РК: ГГММДД + цифра века/пола (XX век: 3-муж/4-жен,
     * XXI век: 5-муж/6-жен) + 4 «порядковые» цифры из хэша номера + контрольная цифра.
     */
    private String buildIin(Persona p, String phone) {
        String[] parts = p.birthIso().split("-");
        String yymmdd = parts[0].substring(2) + parts[1] + parts[2];
        boolean male = "Мужской".equals(p.gender());
        boolean century21 = p.birthIso().compareTo("2000") >= 0;
        char centuryGender = century21 ? (male ? '5' : '6') : (male ? '3' : '4');
        int hash = Math.abs(phone.hashCode());
        String serial = String.format("%04d", hash % 10_000);
        return yymmdd + centuryGender + serial + (hash % 10);
    }
}
