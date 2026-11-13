package com.sairo.be.global.port.prod;

import com.sairo.be.global.port.MailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

@Component
@Profile("prod")
class SesMailSender implements MailSender {
  private final SesV2Client client;
  private final String from;

  SesMailSender(SesV2Client client, @Value("${sairo.delivery.mail.from}") String from) {
    this.client = client;
    this.from = from;
  }

  @Override
  public void send(MailMessage message) {
    client.sendEmail(
        SendEmailRequest.builder()
            .fromEmailAddress(from)
            .destination(Destination.builder().toAddresses(message.to()).build())
            .content(
                EmailContent.builder()
                    .simple(
                        Message.builder()
                            .subject(
                                Content.builder().data(message.subject()).charset("UTF-8").build())
                            .body(
                                Body.builder()
                                    .text(
                                        Content.builder()
                                            .data(message.body())
                                            .charset("UTF-8")
                                            .build())
                                    .build())
                            .build())
                    .build())
            .build());
  }
}
